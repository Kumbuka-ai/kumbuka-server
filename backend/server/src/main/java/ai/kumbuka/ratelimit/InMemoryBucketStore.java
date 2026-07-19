package ai.kumbuka.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process bucket store: one local (JVM-heap) token bucket per key.
 *
 * <p>This implementation is only sound when exactly ONE application
 * instance serves traffic — on N instances the effective limit would
 * silently become N times the configured band. That precondition is not a
 * comment but a boot assertion: {@link RateLimitScaleGate} refuses to start
 * a multi-instance deployment with this store selected.
 *
 * <p>Buckets refill greedily (tokens accrue continuously at
 * {@code refillTokens / refillPeriodSeconds} per second) rather than in
 * period-boundary bursts, which is the natural shape for "burst + refill"
 * write budgets. When a key's band changes at runtime (a tenant override is
 * set or cleared), the next consumption swaps in a freshly filled bucket of
 * the new shape — a momentary full refill on reconfiguration, accepted for
 * config-change rarity.
 *
 * <p>Hygiene bound: the map is pruned of FULL buckets when it grows past
 * {@link #PRUNE_THRESHOLD}. Removing a full bucket is semantically lossless
 * (a re-created bucket starts full), so pruning can never tighten or loosen
 * anyone's effective limit.
 */
@ApplicationScoped
public class InMemoryBucketStore implements RateLimitBucketStore {

    static final int PRUNE_THRESHOLD = 10_000;

    private final ConcurrentMap<String, ShapedBucket> buckets = new ConcurrentHashMap<>();

    /** Clock source; package-private so tests can drive refill deterministically. */
    TimeMeter timeMeter = TimeMeter.SYSTEM_MILLISECONDS;

    private record ShapedBucket(WriteRateBand band, Bucket bucket) {}

    @Override
    public ConsumeResult tryConsume(String bucketKey, WriteRateBand band) {
        ShapedBucket shaped = buckets.compute(bucketKey, (k, existing) ->
            existing != null && existing.band().equals(band) ? existing : create(band));
        ConsumptionProbe probe = shaped.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return ConsumeResult.allow();
        }
        long waitSeconds = Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L);
        pruneIfOversized();
        return ConsumeResult.throttle(waitSeconds);
    }

    private ShapedBucket create(WriteRateBand band) {
        Bucket bucket = Bucket.builder()
            .addLimit(limit -> limit
                .capacity(band.burstCapacity())
                .refillGreedy(band.refillTokens(), Duration.ofSeconds(band.refillPeriodSeconds())))
            .withCustomTimePrecision(timeMeter)
            .build();
        return new ShapedBucket(band, bucket);
    }

    private void pruneIfOversized() {
        if (buckets.size() <= PRUNE_THRESHOLD) {
            return;
        }
        buckets.entrySet().removeIf(e ->
            e.getValue().bucket().getAvailableTokens() >= e.getValue().band().burstCapacity());
    }
}
