package ai.kumbuka.repo;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.overlay.GuidanceOverlay;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
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
    @Inject GuidanceOverlay guidance;

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
        List<Memory> rows = find(jpql.toString(), params).list();
        // Add the built-in guidance entries that apply to this listing,
        // suppressed where a real global row already holds the key
        // (coexistence). The listing counters that read this method (overview
        // totals, per-scope entry counts) inherit the merge automatically —
        // they are the size() of this same list.
        return guidance.mergeIntoShared(rows, scopeSlug, type);
    }

    /** Look up a single shared memory by its {@code logical_id} (Amendment 3,
     *  the wire reference handle). Returns null if it refers to a private row —
     *  pretending it does not exist. */
    public Memory findSharedById(UUID logicalId) {
        return find(
            "logicalId = ?1 and scope.kind != ?2",
            logicalId, ScopeKind.PRIVATE
        ).firstResult();
    }

    /**
     * Console PATCH content edit. {@code editorSubject} is the acting admin's KC
     * {@code sub} — stamped as the last-editor provenance (Amendment 4).
     */
    @Transactional
    public Memory update(UUID logicalId, String content, MemoryType type, String editorSubject) {
        Memory m = findSharedById(logicalId);
        if (m == null) {
            throw new MemoryNotFoundException("shared memory not found: " + logicalId);
        }
        // D-CORE-11 / ADR-0024 §13: locked rows are read-only on this path. There
        // is NO DB UPDATE trigger (Amendment 2) — the console content-edit
        // read-only-ness is guarded HERE (the load-bearing customer guard).
        if (m.lock != MemoryLock.NONE) {
            throw new ProtectedEntryException(
                ProtectedEntryException.Reason.UPDATE_BLOCKED, m.key,
                "memory row is protected (key=" + m.key + ") — locked entries are read-only (D-CORE-11 / ADR-0024 §13)");
        }
        // Amendment 4: an in-place edit stamps last-editor provenance; the
        // first-author owner_subject is never rewritten. @PreUpdate stamps
        // updated_at. version is the @Version optimistic-lock counter.
        if (content != null) m.content = content;
        if (type != null) m.type = type;
        m.updatedBy = editorSubject;
        m.updatedSource = SourceChannel.CONSOLE;
        // §A1.6: surface a concurrent stale edit as a typed 409 here, not a bare
        // exception at commit.
        try {
            getEntityManager().flush();
        } catch (jakarta.persistence.OptimisticLockException ole) {
            throw new MemoryRepository.StaleVersionException(
                "the entry was modified concurrently (stale version) — reload and retry.", ole);
        }
        return m;
    }

    @Transactional
    public int deleteShared(UUID logicalId) {
        // Note the `scope.kind != PRIVATE` guard — the same logical_id in a
        // private scope is NEVER deletable through this code path.
        //
        // D-CORE-11: the BEFORE DELETE trigger (memory_protected_delete_block)
        // catches locked rows below this layer with SQLSTATE P0001. We translate
        // that to a typed ProtectedEntryException so the admin resource can
        // return a clean 409 instead of a raw 500.
        try {
            return (int) delete(
                "logicalId = ?1 and scope.kind != ?2",
                logicalId, ScopeKind.PRIVATE);
        } catch (jakarta.persistence.PersistenceException pe) {
            if (ProtectedDeleteBlockDetector.isProtectedDeleteBlock(pe)) {
                throw new ProtectedEntryException(
                    ProtectedEntryException.Reason.DELETE_BLOCKED,
                    null,
                    "delete blocked: row logical_id=" + logicalId + " is protected (D-CORE-11)");
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
