package ai.kumbuka.ratelimit;

/**
 * A token-bucket band: {@code burstCapacity} tokens available at rest,
 * refilled with {@code refillTokens} tokens every
 * {@code refillPeriodSeconds}. Pure domain value — carries no store or
 * transport vocabulary.
 */
public record WriteRateBand(int burstCapacity, int refillTokens, int refillPeriodSeconds) {

    public WriteRateBand {
        if (burstCapacity <= 0 || refillTokens <= 0 || refillPeriodSeconds <= 0) {
            throw new IllegalArgumentException(
                "write-rate band values must be positive: " + burstCapacity
                    + "/" + refillTokens + "/" + refillPeriodSeconds);
        }
    }
}
