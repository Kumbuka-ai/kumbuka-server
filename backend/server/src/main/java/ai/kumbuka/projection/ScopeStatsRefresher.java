package ai.kumbuka.projection;

import ai.kumbuka.tenancy.TenantBound;
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
 */
@TenantBound
@ApplicationScoped
public class ScopeStatsRefresher {

    private static final Logger LOG = Logger.getLogger(ScopeStatsRefresher.class);

    @Inject EntityManager em;

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
        // RLS scopes both statements to the current tenant. The DELETE
        // clears the prior projection for this tenant; the INSERT
        // recomputes from memory. Private rows are excluded by the
        // WHERE clause (and barred from ever landing here by the
        // scope_kind CHECK on the table).
        em.createNativeQuery("DELETE FROM scope_stats").executeUpdate();

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
            GROUP BY m.tenant_id, m.scope_id, s.slug, s.kind, m.type
            """).executeUpdate();

        long ms = (System.nanoTime() - start) / 1_000_000;
        LOG.debugf("scope_stats refresh: %d row(s) in %d ms", inserted, ms);
    }
}
