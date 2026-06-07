package ai.kumbuka.tenancy;

import java.util.UUID;

/**
 * Resolves the data tenant for the current request scope.
 *
 * <p>This is the <strong>frozen SPI</strong> the commercial multi-tenant
 * edition of kumbuka depends on. The OSS edition ships
 * {@link DefaultSingleTenantResolver}, which always returns the configured
 * singleton tenant. The commercial edition replaces this bean by
 * registering its own {@code @ApplicationScoped TenantResolver} — Quarkus
 * picks the non-default bean automatically (see ADR-0011).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Returns the current data tenant id. <strong>Never null.</strong>
 *       A missing tenant context is a programming error and the
 *       implementation should throw rather than guess.</li>
 *   <li>Stable across the lifetime of a single request transaction. The
 *       wiring guarantees the resolver is called once per transaction
 *       boundary; implementations should not memoize beyond that.</li>
 *   <li>Idempotent and free of side effects. The framework may invoke
 *       this method from a Flyway callback, from an interceptor, and
 *       from the Hibernate session-current-tenant lookup, all within
 *       the same transaction.</li>
 * </ul>
 *
 * <p><strong>Effective tenant.</strong> Application code never reads this
 * resolver directly. Both the Hibernate
 * {@code CurrentTenantIdentifierResolver} and the Postgres session-GUC
 * setter go through {@link TenantContext#current()}, which returns the
 * value bound via {@link TenantContext#bind(UUID)} when present and
 * delegates to this resolver otherwise. That single read point prevents a
 * split brain between Hibernate and the Postgres GUC.
 *
 * @since 1.0.0
 */
public interface TenantResolver {

    /**
     * @return the data tenant id for the current request scope. Never null.
     */
    UUID currentTenant();
}
