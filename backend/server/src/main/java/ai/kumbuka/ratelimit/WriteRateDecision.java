package ai.kumbuka.ratelimit;

/**
 * Outcome of a write-rate check. {@code retryAfterSeconds} is meaningful
 * only when {@code allowed} is false.
 */
public record WriteRateDecision(boolean allowed, long retryAfterSeconds) {

    public static WriteRateDecision allow() {
        return new WriteRateDecision(true, 0);
    }

    public static WriteRateDecision throttle(long retryAfterSeconds) {
        return new WriteRateDecision(false, Math.max(1, retryAfterSeconds));
    }
}
