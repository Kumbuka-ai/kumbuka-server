package ai.kumbuka.erasure;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Phase 3 follow-up — drop the OSS-side data for a fully-purged tenant.
 *
 * <p>This is the OSS counterpart to the ops-console's 30-day purge cron
 * (ADR-0015). Once every member of a tenant has been erased via
 * {@link MemberErasureService#eraseSubject}, the tenant's scopes, its
 * team_settings and its team row are still on disk. This service drops them in
 * dependency order so the tenant leaves no orphans on the core's side.
 *
 * <h3>Delete order</h3>
 *
 * <ol>
 *   <li>{@code user_account} — no FK; defensive cleanup in case the
 *       caller's per-member erase missed one.</li>
 *   <li>{@code team_settings} — FK to scope is {@code ON DELETE SET
 *       NULL}, but the row itself is per-tenant and must go.</li>
 *   <li>{@code scope} — once the team_settings refs are clear.
 *       Cascades {@code scope_stats} via that table's
 *       {@code ON DELETE CASCADE}.</li>
 *   <li>{@code team} — conceptual root, last.</li>
 * </ol>
 *
 * <h3>What this service no longer drops</h3>
 *
 * <p>{@code memory} was step 1 until the memory engine left the core, because
 * {@code memory.scope_id REFERENCES scope(id) ON DELETE RESTRICT} made it the
 * mandatory predecessor of the scope delete. The core does not speak for that
 * table any more, so the step is gone rather than kept as a zero. Two
 * consequences, both deliberate and both the next commission's business: the
 * memory service must be asked to drop its own rows, and until it has, the
 * {@code scope} delete below will be <em>refused</em> by that RESTRICT for any
 * tenant that still holds entries. The conductor of the erasure path is what
 * orders the two; this state ships nowhere without it.
 *
 * <h3>Tenant scoping</h3>
 *
 * <p>Runs under {@link TenantBound}: Hibernate's {@code @TenantId} on
 * the entities + the Postgres GUC pin every JPQL DELETE to the current
 * tenant. The resource-layer's {@code tenant_id} parameter is the
 * misroute guard; this service operates on whatever tenant the
 * resolver hands it.
 *
 * <p>{@code user_account}, {@code team_settings}, and {@code team} are
 * deleted with native SQL via {@link EntityManager}: although they are mapped
 * as JPA entities (each carries {@code @TenantId}), this purge path issues
 * native bulk DELETEs. The native statements carry an explicit
 * {@code WHERE tenant_id = ?} so they still scope correctly.
 *
 */
@ApplicationScoped
@TenantBound
public class TenantDataPurgeService {

    @Inject EntityManager em;

    /** Per-table counts surfaced to the caller and audited. No content. */
    public record PurgeResult(
        int userAccountsDeleted,
        int teamSettingsDeleted,
        int scopesDeleted,
        int teamDeleted) {}

    /**
     * Drop everything tenant-owned in the OSS schema for the current
     * tenant context. Returns the per-table counts so the caller can
     * audit the outcome.
     *
     * <p>Idempotent: every step DELETEs against a {@code WHERE} that
     * narrows by tenant; a re-run on an already-empty tenant returns
     * all zeros.
     */
    @Transactional
    public PurgeResult purgeTenant(String tenantIdLiteral) {
        // Step 1: user_account. Native bulk DELETE (user_account is a JPA
        // entity, but this purge path deletes via SQL); scoped by tenant explicitly.
        final int userAccountsDeleted = em.createNativeQuery(
            "DELETE FROM user_account WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        // Step 2: team_settings (FK to scope is SET NULL so order vs.
        // scope is flexible, but tidy-by-tenant is the cleanest read).
        final int teamSettingsDeleted = em.createNativeQuery(
            "DELETE FROM team_settings WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        // Step 3: scope (cascades scope_stats via ON DELETE CASCADE).
        final int scopesDeleted = (int) Scope.deleteAll();

        // Step 4: team (root).
        final int teamDeleted = em.createNativeQuery(
            "DELETE FROM team WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        return new PurgeResult(
            userAccountsDeleted, teamSettingsDeleted, scopesDeleted, teamDeleted);
    }
}
