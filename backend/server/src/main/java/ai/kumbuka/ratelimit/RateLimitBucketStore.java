package ai.kumbuka.ratelimit;

/**
 * Persistence port for token-bucket state. The limiter policy talks to this
 * interface only; which backend holds the buckets (in-process memory today,
 * a shared distributed store once the deployment scales past one instance)
 * is an adapter choice that must never leak into the policy.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Thread-safe; called on every write request.</li>
 *   <li>A bucket is identified by an opaque {@code bucketKey} and shaped by
 *       the given {@link WriteRateBand}. When the band for an existing key
 *       changes (runtime reconfiguration), the implementation adopts the new
 *       shape on the next call.</li>
 *   <li><strong>Failure mode:</strong> when the backing store is unreachable
 *       or broken, implementations throw {@link RateLimitStoreException}
 *       (or any {@code RuntimeException}). The policy treats every store
 *       failure as DEGRADATION-FAIL-OPEN — the write passes — because a
 *       rate limiter must never be able to take down the system it
 *       protects. Implementations must NOT silently swallow backend loss
 *       and pretend to have counted.</li>
 * </ul>
 */
public interface RateLimitBucketStore {

    /**
     * Try to take one token from the bucket identified by {@code bucketKey}.
     *
     * @return the consumption outcome; when not allowed, carries the
     *         estimated seconds until a token becomes available again
     * @throws RateLimitStoreException when the backing store is unavailable
     */
    ConsumeResult tryConsume(String bucketKey, WriteRateBand band);

    /** Outcome of a single token consumption attempt. */
    record ConsumeResult(boolean allowed, long retryAfterSeconds) {

        public static ConsumeResult allow() {
            return new ConsumeResult(true, 0);
        }

        public static ConsumeResult throttle(long retryAfterSeconds) {
            return new ConsumeResult(false, Math.max(1, retryAfterSeconds));
        }
    }

    /** Signals that the backing bucket store is unreachable or broken. */
    class RateLimitStoreException extends RuntimeException {
        public RateLimitStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
