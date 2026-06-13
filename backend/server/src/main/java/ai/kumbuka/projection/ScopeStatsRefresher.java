package ai.kumbuka.projection;

import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.tenancy.TenantContext;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Recomputes the {@code scope_stats} projection at a fixed cadence.
 *
 * <p>{@code scope_stats} is the only count surface the commercial
 * ops-console reads — the provider DB role has <strong>no
 * {@code GRANT}</strong> on {@code memory} (V6 migration; ADR-0014). The
 * private-exclusion lives in <strong>one</strong> place: the
 * {@code WHERE s.kind != 'private'} predicate in the INSERT below. A
 * defence-in-depth {@code CHECK} on the table itself forbids
 * {@code scope_kind = 'private'} rows from ever being persisted.
 *
 * <p>OSS edition is single-tenant; this refresher resolves the tenant
 * through the standard {@link ai.kumbuka.tenancy.TenantResolver} (the
 * {@link TenantBound} + {@code @Transactional} pair sets the Postgres
 * GUC). The commercial edition swaps the resolver and may extend this
 * bean to iterate every known tenant via {@code TenantContext.bind()}.
 *
 * <p><strong>Tenant isolation, two layers.</strong> {@code scope_stats} is the
 * one tenant-scoped table with no Hibernate {@code @TenantId} entity, so it is
 * written by native SQL and cannot lean on the discriminator filter. RLS (keyed
 * on the {@code app.tenant_id} GUC the {@code @TenantBound} pair sets) is one
 * layer; as defence-in-depth both statements ALSO carry an explicit
 * {@code tenant_id = :tid} predicate bound to {@link TenantContext#current()}.
 * That keeps a single tenant in scope even if the refresher ever ran under a
 * {@code BYPASSRLS} role, and is the invariant the multi-tenant isolation IT
 * asserts. An unscoped {@code DELETE FROM scope_stats} under {@code BYPASSRLS}
 * would wipe every tenant's projection — the predicate prevents that.
 */
@TenantBound
@ApplicationScoped
public class ScopeStatsRefresher {

    private static final Logger LOG = Logger.getLogger(ScopeStatsRefresher.class);

    @Inject EntityManager em;
    @Inject TenantContext tenant;

    /**
     * Configurable schedule (default every 5 minutes). The recompute is
     * cheap on small datasets; tune up for huge tenants if it ever
     * matters. {@code SKIP} concurrent execution so an overrun doesn't
     * pile up.
     */
    @Scheduled(
        every = "{kumbuka.scope-stats.refresh-interval:5m}",
        concurrentExecution = ConcurrentExecution.SKIP)
    @Transactional
    public void refresh() {
        long start = System.nanoTime();
        // Both statements are scoped to exactly the current tenant by an
        // explicit tenant_id predicate (belt) on top of RLS (suspenders). The
        // DELETE clears only this tenant's prior projection; the INSERT
        // recomputes from this tenant's memory. Private rows are excluded by the
        // WHERE clause (and barred from ever landing here by the scope_kind
        // CHECK on the table).
        final String tid = tenant.current().toString();

        em.createNativeQuery("DELETE FROM scope_stats WHERE tenant_id = CAST(:tid AS uuid)")
            .setParameter("tid", tid)
            .executeUpdate();

        int inserted = em.createNativeQuery("""
            INSERT INTO scope_stats
                (tenant_id, scope_id, scope_slug, scope_kind, type,
                 entry_count, last_updated_at)
            SELECT
                m.tenant_id, m.scope_id, s.slug, s.kind, m.type,
                COUNT(*), MAX(m.updated_at)
            FROM memory m
            JOIN scope s ON s.id = m.scope_id
            WHERE s.kind != 'private'
              AND m.tenant_id = CAST(:tid AS uuid)
            GROUP BY m.tenant_id, m.scope_id, s.slug, s.kind, m.type
            """)
            .setParameter("tid", tid)
            .executeUpdate();

        long ms = (System.nanoTime() - start) / 1_000_000;
        LOG.debugf("scope_stats refresh: %d row(s) in %d ms", inserted, ms);
    }
}
