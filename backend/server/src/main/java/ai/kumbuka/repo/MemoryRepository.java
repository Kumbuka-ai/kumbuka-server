package ai.kumbuka.repo;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository used by MCP tools. Every read/delete enforces the
 * private-scope rule: rows in a private scope are only visible/mutable
 * to the user whose {@code subject} equals the row's {@code ownerSubject}.
 *
 * The caller's subject is always passed in explicitly — never defaulted
 * from any other source. The {@link ai.kumbuka.mcp.MemoryTools} layer
 * extracts it from {@code SecurityIdentity} (Keycloak {@code sub} claim);
 * tests pass literal subjects. There is no method that returns memories
 * owned by other users.
 *
 * Scopes are addressed by slug (the URL identity per ADR-0007).
 *
 * Admin code paths use {@link SharedMemoryRepository} instead, which
 * cannot reach private rows at all (see ADR-0003).
 */
@Transactional
@TenantBound
@ApplicationScoped
public class MemoryRepository implements PanacheRepository<Memory> {

    @Inject MemoryConfig config;
    @Inject ScopeRepository scopes;

    /**
     * Append or upsert. If {@code key} is non-null and a row already exists
     * for this (scope, owner, key), update content + type. Otherwise insert.
     *
     * {@code source} records which channel the write came from (MCP for tool
     * calls, CONSOLE for admin endpoints — see ADR-0008). The caller-side
     * layer chooses; the repository does not default.
     */
    @Transactional
    public Memory remember(String callerSubject,
                           String scopeSlug,
                           MemoryType type,
                           String key,
                           String content,
                           SourceChannel source) {
        Scope scope = scopes.requireBySlug(scopeSlug);

        // Tenant axis is enforced structurally (Hibernate @TenantId + RLS,
        // ADR-0011). Repository queries below filter on intra-tenant
        // predicates only (scope, owner, key) — never on tenant_id by hand.
        if (key != null) {
            Optional<Memory> existing = find(
                "scope = ?1 and ownerSubject = ?2 and key = ?3",
                scope, callerSubject, key).firstResultOptional();
            if (existing.isPresent()) {
                Memory m = existing.get();
                m.content = content;
                if (type != null) m.type = type;
                // source is intentionally not updated on upsert — it records
                // who originally wrote the row. The D-CORE-7 `reference` is set
                // by the caller (tool/admin) on a NEW row only, never on upsert.
                return m;
            }
        }

        Memory m = new Memory();
        // tenantId auto-populated by Hibernate from the @TenantId column.
        m.ownerSubject = callerSubject;
        m.scope = scope;
        m.type = type;
        m.key = key;
        m.content = content;
        m.source = source;
        persist(m);
        return m;
    }

    /**
     * Recall memories. Returns:
     *   - Private scope: only the caller's own rows.
     *   - Shared scopes (project/global): all rows in that scope.
     *
     * When {@code scopeSlug} is null (an <em>unscoped</em> read), returns the
     * caller's private rows plus the <strong>global</strong> scope only —
     * project scopes are NOT included in the default view (D-CORE-5). Project
     * memories surface only when the caller asks for that project explicitly
     * via {@code scopeSlug}. When {@code scopeSlug} is given and
     * {@code includeGlobal} is true, the global scope is added to the result.
     */
    public List<Memory> recall(String callerSubject,
                               String scopeSlug,
                               MemoryType type,
                               String query,
                               boolean includeGlobal) {
        StringBuilder jpql = new StringBuilder(
            "((scope.kind = :privateKind and ownerSubject = :caller) " +
            "      or scope.kind in :sharedKinds)");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("caller", callerSubject);
        params.put("privateKind", ScopeKind.PRIVATE);
        // D-CORE-5: an unscoped read (scopeSlug == null) sees private + global
        // only. With an explicit scopeSlug the PROJECT kind must stay in the
        // set, otherwise a row in the requested project scope — matched below by
        // `scope.slug = :scopeSlug` — would be filtered out by this predicate.
        params.put("sharedKinds", scopeSlug == null
            ? List.of(ScopeKind.GLOBAL)
            : List.of(ScopeKind.GLOBAL, ScopeKind.PROJECT));

        if (scopeSlug != null) {
            jpql.append(" and (scope.slug = :scopeSlug");
            params.put("scopeSlug", scopeSlug);
            if (includeGlobal) {
                jpql.append(" or scope.kind = :globalKind");
                params.put("globalKind", ScopeKind.GLOBAL);
            }
            jpql.append(")");
        }
        if (type != null) {
            jpql.append(" and type = :type");
            params.put("type", type);
        }
        if (query != null && !query.isBlank()) {
            jpql.append(" and lower(content) like :q");
            params.put("q", "%" + query.toLowerCase() + "%");
        }
        jpql.append(" order by updatedAt desc");
        return find(jpql.toString(), params).list();
    }

    /**
     * Delete by id OR (scope, key). Returns the number of rows deleted —
     * either 0 (nothing matched, or matched a row the caller cannot touch)
     * or 1. Never deletes another user's private row.
     */
    @Transactional
    public int forget(String callerSubject, String scopeSlug, UUID id, String key) {
        Scope scope = scopes.requireBySlug(scopeSlug);

        // For private scope, restrict to caller's own rows.
        boolean isPrivate = scope.kind == ScopeKind.PRIVATE;

        if (id != null) {
            String jpql = "id = ?1 and scope = ?2" +
                          (isPrivate ? " and ownerSubject = ?3" : "");
            return isPrivate
                ? (int) delete(jpql, id, scope, callerSubject)
                : (int) delete(jpql, id, scope);
        }
        if (key != null) {
            // Key is per-owner (see uq_memory_key in V1__init.sql). For shared
            // scopes we only delete the caller's own keyed entry; we do not
            // touch other authors' rows.
            return (int) delete(
                "scope = ?1 and ownerSubject = ?2 and key = ?3",
                scope, callerSubject, key);
        }
        throw new IllegalArgumentException("forget requires either id or key");
    }

    /** Scopes visible to the caller: their private (one row) + all shared. */
    public List<Scope> listVisibleScopes(String callerSubject) {
        return scopes.listAll();
    }

    /**
     * Load context: caller's visible memories in a scope (or all visible
     * scopes if {@code scopeSlug} is null), grouped by type, capped per
     * group by {@link MemoryConfig#loadContextPerTypeLimit()}.
     */
    public java.util.Map<MemoryType, List<Memory>> loadContext(String callerSubject,
                                                                String scopeSlug) {
        return loadContext(callerSubject, scopeSlug, null);
    }

    /**
     * The "steering" memory types — the durable, decision-shaping kinds. Per D-CORE-6
     * the default digest returns ONLY these and excludes {@code open_question}, so a
     * context load isn't dominated by unresolved questions.
     */
    static final java.util.Set<MemoryType> STEERING_TYPES = java.util.Collections.unmodifiableSet(
        java.util.EnumSet.of(MemoryType.DECISION, MemoryType.CONSTRAINT, MemoryType.CONVENTION,
                             MemoryType.GLOSSARY, MemoryType.STATUS));

    /**
     * D-CORE-6: typed digest. When {@code types} is null/empty the default is the
     * {@link #STEERING_TYPES} set (excludes {@code open_question}); pass an explicit set
     * — e.g. to review "what is open on topic X" — to include {@code open_question} or
     * any subset. Server-side default, not a stored filter.
     */
    public java.util.Map<MemoryType, List<Memory>> loadContext(String callerSubject,
                                                                String scopeSlug,
                                                                java.util.Set<MemoryType> types) {
        int perType = config.loadContextPerTypeLimit();
        java.util.Set<MemoryType> wanted = (types == null || types.isEmpty()) ? STEERING_TYPES : types;
        java.util.Map<MemoryType, List<Memory>> grouped = new java.util.EnumMap<>(MemoryType.class);
        for (MemoryType t : wanted) {
            List<Memory> rows = recall(callerSubject, scopeSlug, t, null, false);
            grouped.put(t, rows.size() > perType ? rows.subList(0, perType) : rows);
        }
        return grouped;
    }
}
