package ai.kumbuka.erasure;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Apply the per-data-class erasure policy from ADR-0015 to memory content
 * within the current tenant context.
 *
 * <p>Three writes, in this order:
 * <ol>
 *   <li><strong>Private memory — deleted in full.</strong>
 *       {@code DELETE FROM memory WHERE owner_subject = ? AND scope.kind = 'private'}.
 *       The private scope row itself is the tenant's singleton (see
 *       {@code V1__init.sql}); only the member's rows in it are removed.</li>
 *   <li><strong>Shared memory authorship — tombstoned.</strong>
 *       {@code UPDATE memory SET owner_subject = tombstone WHERE owner_subject = ? AND scope.kind <> 'private'}.
 *       Content is unchanged; only the personal identifier is severed
 *       (consistent with ADR-0008: server-derived authorship).</li>
 *   <li><strong>Scope provenance — tombstoned.</strong>
 *       {@code UPDATE scope SET created_by = tombstone WHERE created_by = ?}.
 *       Scopes the member created stay (team knowledge); their authorship
 *       metadata is anonymized for the same reason.</li>
 * </ol>
 *
 * <p>All three statements run inside one JTA transaction with
 * {@link TenantBound} active, so Hibernate's {@code @TenantId} filter and
 * the Postgres {@code app.tenant_id} GUC scope each query to the
 * resolver's current tenant.
 */
@ApplicationScoped
@TenantBound
public class MemberErasureService {

    @Inject ErasureConfig config;

    /** Counts returned to the caller. Never contains content — only numbers. */
    public record EraseResult(int privatePurged, int sharedTombstoned, int scopesTombstoned) {}

    /**
     * Discharge the policy for {@code subject} within the current tenant.
     * Idempotent: a second call after a successful run returns all zeros.
     */
    @Transactional
    public EraseResult eraseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject required");
        }
        final String tombstone = config.tombstoneSubject();
        if (tombstone == null || tombstone.isBlank()) {
            throw new IllegalStateException(
                "kumbuka.internal.erasure.tombstone-subject is blank — refusing to erase");
        }
        if (subject.equals(tombstone)) {
            // Defence-in-depth: refusing to operate on the sentinel itself
            // means a misrouted erase can't mass-strip an entire tenant.
            throw new IllegalArgumentException("subject equals the configured tombstone sentinel");
        }

        final int privatePurged = (int) Memory.delete(
            "ownerSubject = ?1 and scope.kind = ?2", subject, ScopeKind.PRIVATE);

        final int sharedTombstoned = Memory.update(
            "ownerSubject = ?1 where ownerSubject = ?2 and scope.kind <> ?3",
            tombstone, subject, ScopeKind.PRIVATE);

        final int scopesTombstoned = Scope.update(
            "createdBy = ?1 where createdBy = ?2", tombstone, subject);

        return new EraseResult(privatePurged, sharedTombstoned, scopesTombstoned);
    }
}
