package ai.kumbuka.repo;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repository used by ADMIN code paths only. Every query JPQL hard-codes
 * {@code scope.kind != PRIVATE}. There is NO method on this class that
 * takes or returns memories in a private scope. There is no method to
 * "switch scope" or "include private". This is intentional — see ADR-0003.
 *
 * If you find yourself wanting to add a method here that touches private
 * rows: stop and re-read ADR-0003. The right answer is to use
 * {@link MemoryRepository} from a non-admin code path instead.
 */
@Transactional
@TenantBound
@ApplicationScoped
public class SharedMemoryRepository implements PanacheRepository<Memory> {

    @Inject MemoryConfig config;
    @Inject ScopeRepository scopes;

    /** All shared memories, optionally filtered by scope slug and type.
     *  Tenant axis is enforced structurally (ADR-0011); this query carries
     *  only the within-tenant `scope.kind != PRIVATE` guard from ADR-0003. */
    public List<Memory> listShared(String scopeSlug, MemoryType type) {
        StringBuilder jpql = new StringBuilder("scope.kind != :privateKind");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("privateKind", ScopeKind.PRIVATE);

        if (scopeSlug != null) {
            jpql.append(" and scope.slug = :scopeSlug");
            params.put("scopeSlug", scopeSlug);
        }
        if (type != null) {
            jpql.append(" and type = :type");
            params.put("type", type);
        }
        jpql.append(" order by updatedAt desc");
        return find(jpql.toString(), params).list();
    }

    /** Look up a single memory by id. Returns null if the id refers to a
     *  private row — pretending it does not exist. */
    public Memory findSharedById(UUID id) {
        return find(
            "id = ?1 and scope.kind != ?2",
            id, ScopeKind.PRIVATE
        ).firstResult();
    }

    @Transactional
    public Memory update(UUID id, String content, MemoryType type) {
        Memory m = findSharedById(id);
        if (m == null) {
            throw new MemoryNotFoundException("shared memory not found: " + id);
        }
        // D-CORE-11 / ADR-0024 §13: locked rows are read-only on this path. The
        // DB UPDATE trigger only blocks move/rename of a locked row (not content
        // edit, so the SYSTEM re-seed via remember() still works); the console
        // content-edit read-only-ness is guarded HERE — this is the only caller.
        if (m.lock != MemoryLock.NONE) {
            throw new ProtectedEntryException(
                ProtectedEntryException.Reason.UPDATE_BLOCKED, m.key,
                "memory row is protected (key=" + m.key + ") — locked entries are read-only (D-CORE-11 / ADR-0024 §13)");
        }
        // §A1.6 (CoW version-bump in CE): CE is head-overwrite, last-write-wins
        // (ADR-0024 §4) — a content edit overwrites the head in place and does
        // NOT bump `version` (it stays at 1). The version column exists as the
        // reserved §6 coordinate; EE activates real per-version snapshots +
        // optimistic-lock semantics. Decision recorded in the V16 handover for
        // Concept ratification.
        if (content != null) m.content = content;
        if (type != null) m.type = type;
        return m;
    }

    @Transactional
    public int deleteShared(UUID id) {
        // Note the `scope.kind != PRIVATE` guard — the same id in a private
        // scope is NEVER deletable through this code path.
        //
        // D-CORE-11: the BEFORE DELETE trigger (memory_protected_delete_block)
        // catches protected rows below this layer with SQLSTATE P0001. We
        // translate that to a typed ProtectedEntryException so the admin
        // resource can return a clean 409 instead of a raw 500.
        try {
            return (int) delete(
                "id = ?1 and scope.kind != ?2",
                id, ScopeKind.PRIVATE);
        } catch (jakarta.persistence.PersistenceException pe) {
            if (ProtectedDeleteBlockDetector.isProtectedDeleteBlock(pe)) {
                throw new ProtectedEntryException(
                    ProtectedEntryException.Reason.DELETE_BLOCKED,
                    null,
                    "delete blocked: row id=" + id + " is protected (D-CORE-11)");
            }
            throw pe;
        }
    }

    /**
     * Guard for callers that look up by scope slug: a private scope cannot
     * be listed through this repository at all. We throw so the bug is
     * loud rather than silently returning an empty list.
     */
    public Scope requireSharedScope(String scopeSlug) {
        Scope s = scopes.requireBySlug(scopeSlug);
        if (s.kind == ScopeKind.PRIVATE) {
            throw new IllegalArgumentException(
                "private scope cannot be accessed via admin code paths: " + scopeSlug);
        }
        return s;
    }

    public static class MemoryNotFoundException extends RuntimeException {
        public MemoryNotFoundException(String m) { super(m); }
    }
}
