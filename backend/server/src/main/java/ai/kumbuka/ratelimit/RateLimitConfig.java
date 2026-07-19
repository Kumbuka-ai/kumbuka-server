package ai.kumbuka.ratelimit;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Deployment-time configuration of the write-rate limiter. Per-tenant
 * runtime overrides live in the {@code tenant_limits} table (managed via
 * the internal limits endpoint), not here.
 *
 * <p>The default band is sized to the fastest plausible LEGITIMATE agent
 * write cadence times a headroom factor — an assistant seeding a scope at
 * machine speed during onboarding must pass unthrottled — not to human
 * typing speed. See {@code application.properties} for the numbers and
 * their rationale.
 */
@ConfigMapping(prefix = "kumbuka.rate-limit")
public interface RateLimitConfig {

    String STORE_IN_MEMORY = "in-memory";
    String STORE_SHARED = "shared";

    /**
     * Bucket store selector: {@code in-memory} (single-instance only,
     * enforced at boot by {@link RateLimitScaleGate}) or {@code shared}
     * (reserved for a future distributed store implementation).
     */
    @WithName("store")
    @WithDefault(STORE_IN_MEMORY)
    String store();

    /** Default per-principal burst capacity (tokens at rest). */
    @WithName("default-burst-capacity")
    @WithDefault("600")
    int defaultBurstCapacity();

    /** Default per-principal refill amount per period. */
    @WithName("default-refill-tokens")
    @WithDefault("120")
    int defaultRefillTokens();

    /** Default refill period in seconds. */
    @WithName("default-refill-period-seconds")
    @WithDefault("60")
    int defaultRefillPeriodSeconds();

    default WriteRateBand defaultBand() {
        return new WriteRateBand(
            defaultBurstCapacity(), defaultRefillTokens(), defaultRefillPeriodSeconds());
    }
}
