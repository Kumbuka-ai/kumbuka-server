package ai.kumbuka.erasure;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Apply the per-data-class erasure policy from ADR-0015 to the administrative
 * data the core owns, within the current tenant context.
 *
 * <p>One write: <strong>scope provenance — tombstoned.</strong>
 * {@code UPDATE scope SET created_by = tombstone WHERE created_by = ?}. Scopes
 * the member created stay (team knowledge); their authorship metadata is
 * anonymized (consistent with ADR-0008: server-derived authorship).
 *
 * <p><strong>The content half of the policy is no longer discharged here.</strong>
 * Deleting a member's private entries and tombstoning their shared authorship
 * were the first two steps of this service until the memory engine left the
 * core. They belong to the memory service now, and the conductor that calls it
 * across the participants is the next commission — until that conductor exists
 * the erasure path is incomplete, which is why this state ships nowhere.
 *
 * <p>The statement runs inside one JTA transaction with {@link TenantBound}
 * active, so Hibernate's {@code @TenantId} filter and the Postgres
 * {@code app.tenant_id} GUC scope the query to the resolver's current tenant.
 */
@ApplicationScoped
@TenantBound
public class MemberErasureService {

    @Inject ErasureConfig config;

    /** Counts returned to the caller. Never contains content — only numbers. */
    public record EraseResult(int scopesTombstoned) {}

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

        final int scopesTombstoned = Scope.update(
            "createdBy = ?1 where createdBy = ?2", tombstone, subject);

        return new EraseResult(scopesTombstoned);
    }
}
