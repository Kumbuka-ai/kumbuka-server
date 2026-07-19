package ai.kumbuka.ratelimit;

import java.util.Optional;

/**
 * The write-rate limits in force for one tenant: the per-principal band
 * (tenant override when set, else the deployment default) and the optional
 * tenant-aggregate band (empty = no aggregate limit, the shipped default).
 */
public record EffectiveWriteRateLimits(
    WriteRateBand principalBand,
    Optional<WriteRateBand> tenantAggregateBand,
    boolean overridden) {
}
