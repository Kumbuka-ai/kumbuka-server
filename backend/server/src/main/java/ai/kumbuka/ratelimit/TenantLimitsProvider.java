package ai.kumbuka.ratelimit;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the {@link EffectiveWriteRateLimits} for a tenant: the
 * {@code tenant_limits} override row when one exists, else the
 * deployment-default band.
 *
 * <p>The read deliberately bypasses Hibernate: the limiter evaluates BEFORE
 * the per-request tenant binding exists (that is the point — a throttled
 * request must not reach the tenant-bound machinery), so an ORM read here
 * would prematurely trigger tenant resolution. Direct JDBC against the
 * open-read {@code tenant_limits} SELECT policy (V19) mirrors the
 * pre-binding team-alias routing lookup.
 *
 * <p>Results are cached for {@link #CACHE_TTL_MILLIS} per tenant so the
 * write hot path costs one map lookup, not one query per request; the
 * internal limits endpoint {@linkplain #invalidate(UUID) invalidates} the
 * entry on every configuration change, so a PATCH takes effect on the next
 * write (the TTL is only the backstop for missed invalidation). In-process
 * invalidation is sufficient by construction: the in-memory limiter store
 * is boot-gated to a single instance, and the config-write endpoint runs in
 * the same process.
 *
 * <p>Failure mode: when the config read fails, the limiter falls back to
 * the deployment DEFAULT band (logged at WARN). This is deliberately not
 * fail-open — losing sight of an override must not suspend the limiter —
 * and not fail-closed either; store-outage fail-open concerns the BUCKET
 * store, not this config read.
 */
@ApplicationScoped
public class TenantLimitsProvider {

    private static final Logger LOG = Logger.getLogger(TenantLimitsProvider.class);

    static final long CACHE_TTL_MILLIS = 60_000;

    private static final String SELECT_LIMITS = """
        SELECT write_burst_capacity, write_refill_tokens, write_refill_period_seconds,
               tenant_write_burst_capacity, tenant_write_refill_tokens, tenant_write_refill_period_seconds
          FROM tenant_limits
         WHERE tenant_id = ?""";

    @Inject AgroalDataSource dataSource;
    @Inject RateLimitConfig config;

    private final ConcurrentMap<UUID, CachedLimits> cache = new ConcurrentHashMap<>();

    private record CachedLimits(EffectiveWriteRateLimits limits, long loadedAtMillis) {}

    /** Effective limits for {@code tenantId}; never null, never throws. */
    public EffectiveWriteRateLimits effectiveLimits(UUID tenantId) {
        long now = System.currentTimeMillis();
        CachedLimits cached = cache.get(tenantId);
        if (cached != null && now - cached.loadedAtMillis() < CACHE_TTL_MILLIS) {
            return cached.limits();
        }
        EffectiveWriteRateLimits fresh = load(tenantId);
        cache.put(tenantId, new CachedLimits(fresh, now));
        return fresh;
    }

    /** Drop the cached entry so the next write re-reads the configuration. */
    public void invalidate(UUID tenantId) {
        cache.remove(tenantId);
    }

    /** The deployment-default limits (no tenant override, no aggregate band). */
    public EffectiveWriteRateLimits defaults() {
        return new EffectiveWriteRateLimits(config.defaultBand(), Optional.empty(), false);
    }

    private EffectiveWriteRateLimits load(UUID tenantId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_LIMITS)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return defaults();
                }
                WriteRateBand principal = readBand(rs,
                    "write_burst_capacity", "write_refill_tokens", "write_refill_period_seconds");
                WriteRateBand aggregate = readBand(rs,
                    "tenant_write_burst_capacity", "tenant_write_refill_tokens",
                    "tenant_write_refill_period_seconds");
                return new EffectiveWriteRateLimits(
                    principal != null ? principal : config.defaultBand(),
                    Optional.ofNullable(aggregate),
                    principal != null);
            }
        } catch (SQLException e) {
            LOG.warnf("tenant_limits read failed for tenant %s — applying default band (%s)",
                tenantId, e.getMessage());
            return defaults();
        }
    }

    private static WriteRateBand readBand(ResultSet rs, String burstCol, String tokensCol,
                                          String periodCol) throws SQLException {
        int burst = rs.getInt(burstCol);
        if (rs.wasNull()) {
            return null;
        }
        int tokens = rs.getInt(tokensCol);
        int period = rs.getInt(periodCol);
        return new WriteRateBand(burst, tokens, period);
    }
}
