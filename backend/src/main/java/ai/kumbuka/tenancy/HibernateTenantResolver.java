package ai.kumbuka.tenancy;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.tenant.TenantResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Hibernate's per-session tenant lookup, wired through Quarkus' Hibernate
 * extension. Delegates to {@link TenantContext#current()} — never to the
 * kumbuka {@link ai.kumbuka.tenancy.TenantResolver} directly — so a
 * programmatic {@code bind()} steers Hibernate the same way it steers
 * the Postgres GUC (ADR-0011 §M1).
 *
 * <p>Implements {@link io.quarkus.hibernate.orm.runtime.tenant.TenantResolver}
 * (Quarkus' Hibernate integration interface — distinct from our own SPI).
 * Hibernate sees a string tenant id round-tripped from our UUID.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class HibernateTenantResolver implements TenantResolver {

    @Inject TenantContext context;

    @Override
    public String getDefaultTenantId() {
        // No fallback — every session must arrive with a bound tenant.
        // TenantRequestFilter + @TenantBound guarantee that for HTTP
        // flows; tests use TenantContext.bind() before opening a
        // transaction.
        return null;
    }

    @Override
    public String resolveTenantId() {
        return context.current().toString();
    }
}
