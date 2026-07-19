package ai.kumbuka.ratelimit;

import ai.kumbuka.tenancy.TenantResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * The write-rate limiting policy: decides, for one authenticated principal,
 * whether a write may proceed. Pure domain — no transport, protocol, or
 * store vocabulary. Both write surfaces (the assistant-facing tool adapter
 * and the console write API) call this through their own thin adapters,
 * which run AFTER token validation and BEFORE the per-request tenant
 * binding, so a throttled request never reaches the tenant-bound machinery.
 *
 * <h3>Buckets</h3>
 * <ul>
 *   <li><strong>Per-principal</strong> (always on): keyed by the validated
 *       token subject — pure token material, no lookup. This is the
 *       enforcing bucket.</li>
 *   <li><strong>Tenant-aggregate</strong> (inert by default): keyed by the
 *       tenant, applied only when a tenant's configuration sets an
 *       aggregate band. Activation is a configuration value, never a code
 *       change.</li>
 * </ul>
 *
 * <h3>Tenant identity for the threshold lookup</h3>
 * The per-tenant threshold needs the tenant id. It comes from
 * {@link TenantResolver#currentTenant()} — a static config value in the
 * single-tenant edition, and an alias lookup the resolver already caches
 * in-process in the hosted overlay — so the steady-state throttle path
 * performs no database work. When resolution fails, the check falls back
 * to the default band and lets the request die where it dies today (at the
 * tenant binding), never changing non-limit outcomes.
 *
 * <h3>Failure mode: degradation-fail-open</h3>
 * Any bucket-store failure lets the write PASS (logged at WARN). A rate
 * limiter must never become the lever that takes down the system it
 * protects. For the in-process store this is near-vacuous, but the
 * contract is encoded here so a future shared-store adapter inherits it.
 *
 * <h3>Logging</h3>
 * Throttle events are ephemeral WARN log lines carrying identifiers only,
 * never content. There is deliberately NO persisted throttle history, NO
 * per-member counter, and NO metric series here — rate limiting must not
 * become activity monitoring.
 */
@ApplicationScoped
public class RateLimitPolicy {

    private static final Logger LOG = Logger.getLogger(RateLimitPolicy.class);

    private static final String PRINCIPAL_KEY_PREFIX = "sub:";
    private static final String TENANT_KEY_PREFIX = "tenant:";

    @Inject RateLimitBucketStore store;
    @Inject TenantLimitsProvider limitsProvider;
    @Inject TenantResolver tenantResolver;

    /**
     * Check one write by {@code principalSubject} (the validated token
     * subject). Never throws; never blocks a write for any reason other
     * than an exhausted bucket.
     *
     * <p>Suppressed S2221 (don't catch RuntimeException): the broad catch
     * IS the degradation-fail-open contract — whatever the store adapter
     * throws, the limiter must open rather than block writes or leak the
     * failure into the request.
     */
    @SuppressWarnings("java:S2221")
    public WriteRateDecision checkWrite(String principalSubject) {
        if (principalSubject == null || principalSubject.isBlank()) {
            // No authenticated subject — not this policy's concern; the
            // security layer rejects the request on its own.
            return WriteRateDecision.allow();
        }
        UUID tenant = resolveTenantOrNull();
        EffectiveWriteRateLimits limits = tenant == null
            ? limitsProvider.defaults()
            : limitsProvider.effectiveLimits(tenant);
        try {
            RateLimitBucketStore.ConsumeResult principal =
                store.tryConsume(PRINCIPAL_KEY_PREFIX + principalSubject, limits.principalBand());
            if (!principal.allowed()) {
                LOG.warnf("write throttled (principal bucket): tenant=%s subject=%s retryAfterSeconds=%d",
                    tenant, principalSubject, principal.retryAfterSeconds());
                return WriteRateDecision.throttle(principal.retryAfterSeconds());
            }
            if (tenant != null && limits.tenantAggregateBand().isPresent()) {
                RateLimitBucketStore.ConsumeResult aggregate = store.tryConsume(
                    TENANT_KEY_PREFIX + tenant, limits.tenantAggregateBand().get());
                if (!aggregate.allowed()) {
                    LOG.warnf("write throttled (tenant aggregate bucket): tenant=%s subject=%s retryAfterSeconds=%d",
                        tenant, principalSubject, aggregate.retryAfterSeconds());
                    return WriteRateDecision.throttle(aggregate.retryAfterSeconds());
                }
            }
            return WriteRateDecision.allow();
        } catch (RuntimeException storeFailure) {
            // Degradation-fail-open: the limiter opens, the write passes.
            LOG.warnf("rate-limit bucket store unavailable — failing open (writes pass unthrottled): %s",
                storeFailure.getMessage());
            return WriteRateDecision.allow();
        }
    }

    @SuppressWarnings("java:S2221")
    private UUID resolveTenantOrNull() {
        try {
            return tenantResolver.currentTenant();
        } catch (RuntimeException resolutionFailure) {
            // Resolution failures are not the limiter's to answer; the
            // request will be rejected by the tenant binding downstream.
            return null;
        }
    }
}
