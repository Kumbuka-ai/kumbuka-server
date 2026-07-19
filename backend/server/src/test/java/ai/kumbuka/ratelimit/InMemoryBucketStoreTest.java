package ai.kumbuka.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic bucket mechanics: burst exhaustion, greedy refill over the
 * configured period, and shape adoption on a band change. Uses a fake
 * {@link TimeMeter} so refill is driven by the test clock, not wall time —
 * every assertion is exact and repeatable, no sleeping.
 */
class InMemoryBucketStoreTest {

    private static final WriteRateBand BAND_3_PER_MINUTE = new WriteRateBand(3, 3, 60);

    private final AtomicLong nowNanos = new AtomicLong(0);
    private InMemoryBucketStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryBucketStore();
        store.timeMeter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return nowNanos.get();
            }

            @Override
            public boolean isWallClockBased() {
                return false;
            }
        };
    }

    @Test
    void burstIsConsumedThenThrottled() {
        for (int i = 0; i < 3; i++) {
            assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed())
                .as("write %d within burst capacity", i + 1)
                .isTrue();
        }
        RateLimitBucketStore.ConsumeResult fourth = store.tryConsume("k", BAND_3_PER_MINUTE);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isBetween(1L, 20L);
    }

    @Test
    void refillRestoresCapacityOverThePeriod() {
        for (int i = 0; i < 3; i++) {
            store.tryConsume("k", BAND_3_PER_MINUTE);
        }
        assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed()).isFalse();

        // Greedy refill: 3 tokens per 60s = one token every 20s.
        nowNanos.addAndGet(20_000_000_000L);
        assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed())
            .as("one token refilled after a third of the period")
            .isTrue();
        assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed()).isFalse();

        // A full period restores the full burst.
        nowNanos.addAndGet(60_000_000_000L);
        for (int i = 0; i < 3; i++) {
            assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed())
                .as("write %d after full refill", i + 1)
                .isTrue();
        }
        assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed()).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKey() {
        for (int i = 0; i < 3; i++) {
            store.tryConsume("subject-a", BAND_3_PER_MINUTE);
        }
        assertThat(store.tryConsume("subject-a", BAND_3_PER_MINUTE).allowed()).isFalse();
        assertThat(store.tryConsume("subject-b", BAND_3_PER_MINUTE).allowed())
            .as("another key has its own untouched bucket")
            .isTrue();
    }

    @Test
    void bandChangeSwapsInAFreshBucketOfTheNewShape() {
        for (int i = 0; i < 3; i++) {
            store.tryConsume("k", BAND_3_PER_MINUTE);
        }
        assertThat(store.tryConsume("k", BAND_3_PER_MINUTE).allowed()).isFalse();

        // Runtime reconfiguration: next consumption sees the new band.
        WriteRateBand widened = new WriteRateBand(10, 10, 60);
        assertThat(store.tryConsume("k", widened).allowed())
            .as("new band takes effect immediately (fresh bucket)")
            .isTrue();
    }
}
