package ai.kumbuka.erasure;

import ai.kumbuka.domain.Memory;
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
 * {@link MemberErasureService#eraseSubject}, the lawful-basis purge is
 * already discharged — but the tenant's <em>shared</em> entries (with
 * tombstoned authorship), its scopes, its team_settings, and its team
 * row are still on disk. This service drops the lot in dependency order
 * so the tenant leaves no orphans.
 *
 * <h3>Delete order</h3>
 *
 * <ol>
 *   <li>{@code memory} — first release the D-CORE-11 delete-lock on this
 *       tenant's protected system-seed rows (see the lock note below), then
 *       delete. Must precede {@code scope} because
 *       {@code memory.scope_id REFERENCES scope(id) ON DELETE RESTRICT}.</li>
 *   <li>{@code user_account} — no FK; defensive cleanup in case the
 *       caller's per-member erase missed one.</li>
 *   <li>{@code team_settings} — FK to scope is {@code ON DELETE SET
 *       NULL}, but the row itself is per-tenant and must go.</li>
 *   <li>{@code scope} — once memory + team_settings refs are clear.
 *       Cascades {@code scope_stats} via that table's
 *       {@code ON DELETE CASCADE}.</li>
 *   <li>{@code team} — conceptual root, last.</li>
 * </ol>
 *
 * <h3>Tenant scoping</h3>
 *
 * <p>Runs under {@link TenantBound}: Hibernate's {@code @TenantId} on
 * the entities + the Postgres GUC pin every JPQL DELETE to the current
 * tenant. The resource-layer's {@code tenant_id} parameter is the
 * misroute guard; this service operates on whatever tenant the
 * resolver hands it.
 *
 * <p>{@code user_account}, {@code team_settings}, and {@code team} use
 * native SQL via {@link EntityManager} because they are not mapped as
 * JPA entities in this module — only memory + scope are. The native
 * statements carry an explicit {@code WHERE tenant_id = ?} so they
 * still scope correctly.
 *
 * <h3>The D-CORE-11 delete-lock, and why teardown clears it</h3>
 *
 * <p>The system-seed {@code how-to-kumbuka.*} mnemonics carry
 * {@code memory.lock IN ('system','admin')}; the
 * {@code memory_protected_delete_block} trigger (V12/V16, ADR-0024 §13)
 * refuses to DELETE any such row so the seed stays unfalsifiable
 * <em>inside a live tenant</em>. A full-tenant teardown is the ratified
 * exception: the tenant ceases to exist, so its seeds go with it (they are
 * not billing data). We therefore release the lock (to the only
 * non-protected value, {@code 'none'}) on this tenant's rows FIRST, then
 * delete — within the SAME transaction, so the unlock never outlives the
 * delete. There is no UPDATE trigger on {@code memory}, so the lock release
 * is permitted at the DB layer; the app-layer lock guards
 * (MemoryRepository / MemoryTools) are intentionally bypassed on this
 * teardown-only path. Without this, {@code purgeTenant} raised P0001 and the
 * whole cascade aborted, leaving the tenant un-purgeable (both the 30-day
 * cron and the emergency hard-delete).
 */
@ApplicationScoped
@TenantBound
public class TenantDataPurgeService {

    @Inject EntityManager em;

    /** Per-table counts surfaced to the caller and audited. No content. */
    public record PurgeResult(
        int memoryDeleted,
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
        // Step 0: release the D-CORE-11 delete-lock on this tenant's protected
        // system-seed rows (those locked as system or admin) so the teardown
        // delete can proceed. The lock keeps the how-to seeds unfalsifiable
        // inside a LIVE tenant, whereas a full-tenant teardown is the ratified
        // exception. We clear the lock to none, the only non-protected value, and
        // Step 1 then drops the rows in the same transaction, so the unlock never
        // outlives the delete. This runs as native SQL scoped to the tenant, like
        // the other steps, and there is no update trigger so the DB layer allows
        // it. See the class javadoc for the doctrine.
        em.createNativeQuery(
            "UPDATE memory SET lock = 'none' "
          + "WHERE tenant_id = CAST(?1 AS uuid) AND lock IN ('system', 'admin')")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        // Step 1: memory (must precede scope).
        final int memoryDeleted = (int) Memory.deleteAll();

        // Step 2: user_account. Native because user_account isn't a JPA
        // entity in this module; we still scope by tenant explicitly.
        final int userAccountsDeleted = em.createNativeQuery(
            "DELETE FROM user_account WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        // Step 3: team_settings (FK to scope is SET NULL so order vs.
        // scope is flexible, but tidy-by-tenant is the cleanest read).
        final int teamSettingsDeleted = em.createNativeQuery(
            "DELETE FROM team_settings WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        // Step 4: scope (cascades scope_stats via ON DELETE CASCADE).
        final int scopesDeleted = (int) Scope.deleteAll();

        // Step 5: team (root).
        final int teamDeleted = em.createNativeQuery(
            "DELETE FROM team WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, tenantIdLiteral)
            .executeUpdate();

        return new PurgeResult(
            memoryDeleted, userAccountsDeleted, teamSettingsDeleted,
            scopesDeleted, teamDeleted);
    }
}
