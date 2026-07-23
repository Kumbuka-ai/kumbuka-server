package ai.kumbuka.repo;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
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

    /** Author-independent shared lookup (A1.3 (1)): one canonical live head per key. */
    private static final String SHARED_KEY_LOOKUP = "scope = ?1 and key = ?2";

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
        assertNoProtectedConflict(scope, key, source);

        // Tenant axis is enforced structurally (Hibernate @TenantId + RLS,
        // ADR-0011). Repository queries below filter on intra-tenant predicates
        // only — never on tenant_id by hand. The upsert lookup is scope-kind-
        // differentiated to match the V16 partial unique indexes (A1.3 (1)):
        //   - SHARED (global/project): author-independent (scope, key) — there is
        //     ONE canonical live head per key, so a second author's keyed write
        //     UPDATES that head rather than inserting a parallel row the shared
        //     unique index would then hard-reject.
        //   - PRIVATE: per-author (scope, owner, key) — each owner keeps their
        //     own keyspace (the private unique index is owner-inclusive).
        // D-CORE-16 keeps THIS path an intentional upsert.
        if (key != null) {
            boolean privateScope = scope.kind == ScopeKind.PRIVATE;
            Optional<Memory> existing = privateScope
                ? find("scope = ?1 and ownerSubject = ?2 and key = ?3",
                       scope, callerSubject, key).firstResultOptional()
                : find(SHARED_KEY_LOOKUP,
                       scope, key).firstResultOptional();
            if (existing.isPresent()) {
                Memory m = existing.get();
                m.content = content;
                if (type != null) m.type = type;
                // D-CORE-11: a SYSTEM re-seed upgrades the row in place —
                // ensures the live johannesbayer how-to entries (already
                // present as unprotected conventions) flip to the system lock on
                // the first seed run without producing duplicates. Only the lock
                // flips: `source` is the first-write channel and immutable
                // (updatable = false on the mapping), so the row keeps its
                // original channel.
                if (source == SourceChannel.SYSTEM) {
                    m.lock = MemoryLock.SYSTEM;
                }
                // `source`/`owner_subject` are the FIRST-write authorship and are
                // intentionally not updated (Amendment 4). The in-place edit
                // stamps the LAST-editor provenance instead. The D-CORE-7
                // `reference` is set by the caller on a NEW row only.
                m.updatedBy = callerSubject;
                m.updatedSource = source;
                // §A1.6 optimistic locking: force the @Version check now so a
                // concurrent stale edit surfaces as a typed conflict here, not a
                // bare exception at commit (and not -32603 on the MCP surface).
                flushDetectingStaleVersion();
                return m;
            }
        }

        return insertNew(callerSubject, scope, type, key, content, source);
    }

    /**
     * §A1.6: flush the persistence context and translate Hibernate's optimistic-
     * lock failure into a typed {@link StaleVersionException} (mapped to a 409 on
     * the console path and a typed tool error on the MCP path). A no-op when the
     * loaded {@code version} still matches the row.
     */
    private void flushDetectingStaleVersion() {
        try {
            getEntityManager().flush();
        } catch (jakarta.persistence.OptimisticLockException ole) {
            throw new StaleVersionException(
                "the entry was modified concurrently (stale version) — reload and retry.", ole);
        }
    }

    /**
     * D-CORE-16: the console/admin "new entry" create path. Unlike {@link
     * #remember} (the MCP upsert-by-key), this NEVER overwrites: if {@code key}
     * already exists in the scope — <b>author-independent</b> (one key, one
     * meaning per scope) — it throws {@link KeyExistsException} (→ 409
     * KEY_EXISTS) so the curator is offered a rename instead of silently
     * replacing the prior row (closes dogfood-21). Keyless entries never collide.
     */
    @Transactional
    public Memory createShared(String callerSubject,
                               String scopeSlug,
                               MemoryType type,
                               String key,
                               String content,
                               SourceChannel source) {
        Scope scope = scopes.requireBySlug(scopeSlug);
        assertNoProtectedConflict(scope, key, source);
        assertKeyFree(scope, key, null);
        return insertNew(callerSubject, scope, type, key, content, source);
    }

    /**
     * D-CORE-17: atomically re-home a shared entry into another shared scope.
     * "Everything preserved, only the scope changes." Admin op; the resource
     * gates the role + excludes private. Reuses the D-CORE-16 author-independent
     * key-collision guard against the target. Protected (D-CORE-11) entries are
     * not remappable. {@code newKey} (optional) lets the admin remap under a
     * different key to dodge a target collision.
     */
    @Transactional
    public Memory remap(Memory entry, String targetSlug, String newKey) {
        if (entry.lock != MemoryLock.NONE) {
            throw new ProtectedEntryException(
                ProtectedEntryException.Reason.UPSERT_BLOCKED, entry.key,
                "locked entries (ADR-0024 §13 / D-CORE-11) cannot be re-homed.");
        }
        Scope target = scopes.requireBySlug(targetSlug);
        if (target.kind == ScopeKind.PRIVATE) {
            throw new RemapPrivateForbiddenException(
                "private is not a remap endpoint (private-memory guarantee, P1).");
        }
        String effectiveKey = (newKey != null && !newKey.isBlank()) ? newKey : entry.key;
        assertNoProtectedConflict(target, effectiveKey, entry.source);
        assertKeyFree(target, effectiveKey, entry.logicalId);
        entry.scope = target;
        if (newKey != null && !newKey.isBlank()) entry.key = newKey;
        return entry;
    }

    /**
     * D-CORE-11 guard: a non-system caller must not write a key reserved by a
     * protected system-seed row (the unique index would otherwise let them
     * shadow it with a parallel row, invisible to read paths).
     */
    private void assertNoProtectedConflict(Scope scope, String key, SourceChannel source) {
        if (key != null && source != SourceChannel.SYSTEM) {
            boolean protectedConflict = find(
                "scope = ?1 and key = ?2 and lock = ?3",
                scope, key, MemoryLock.SYSTEM).firstResultOptional().isPresent();
            if (protectedConflict) {
                throw new ProtectedEntryException(
                    ProtectedEntryException.Reason.UPSERT_BLOCKED, key,
                    "key '" + key + "' is reserved by a protected system-seed entry " +
                    "(D-CORE-11). The system seed cannot be overwritten or shadowed " +
                    "by an interactive write.");
            }
        }
    }

    /**
     * D-CORE-16: reject a key that already exists in the scope, author-independent
     * (one key, one meaning per scope). {@code excludeId} skips the row being
     * moved/edited so a same-key remap of the row itself doesn't self-collide.
     * Null/blank key never collides (keyless entries are allowed to repeat).
     */
    private void assertKeyFree(Scope scope, String key, java.util.UUID excludeLogicalId) {
        if (key == null || key.isBlank()) return;
        boolean exists = excludeLogicalId == null
            ? find(SHARED_KEY_LOOKUP, scope, key).firstResultOptional().isPresent()
            : find("scope = ?1 and key = ?2 and logicalId != ?3", scope, key, excludeLogicalId).firstResultOptional().isPresent();
        if (exists) {
            throw new KeyExistsException(key,
                "an entry with key '" + key + "' already exists in scope '" + scope.slug + "'.");
        }
    }

    private Memory insertNew(String callerSubject, Scope scope, MemoryType type,
                             String key, String content, SourceChannel source) {
        Memory m = new Memory();
        // tenantId auto-populated by Hibernate from the @TenantId column.
        m.ownerSubject = callerSubject;
        m.scope = scope;
        m.type = type;
        m.key = key;
        m.content = content;
        m.source = source;
        m.lock = (source == SourceChannel.SYSTEM) ? MemoryLock.SYSTEM : MemoryLock.NONE;
        persist(m);
        return m;
    }

    /** D-CORE-16: a console create / remap hit an existing key in the scope.
     *  Mapped to HTTP 409 KEY_EXISTS for REST callers. */
    public static class KeyExistsException extends RuntimeException {
        private final String key;
        public KeyExistsException(String key, String message) { super(message); this.key = key; }
        public String key() { return key; }
    }

    /** D-CORE-17: private was given as a remap source/target — structurally
     *  forbidden (P1). Mapped to HTTP 422 REMAP_PRIVATE_FORBIDDEN. */
    public static class RemapPrivateForbiddenException extends RuntimeException {
        public RemapPrivateForbiddenException(String message) { super(message); }
    }

    /** §A1.6 optimistic locking: a concurrent edit advanced the {@code version}
     *  under a stale writer. Mapped to HTTP 409 STALE_VERSION on the console path
     *  and a typed tool error on the MCP path. */
    public static class StaleVersionException extends RuntimeException {
        public StaleVersionException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Seed a protected system-seed mnemonic.
     *
     * <p>Convenience wrapper around {@link #remember} with the system
     * sentinel as owner_subject and {@code SourceChannel.SYSTEM} as the
     * channel — keeps the seeder code from having to know the sentinel.
     * Idempotent by (scope, key): seeding only ever creates <em>missing</em>
     * rows. A row that already exists under the key — an earlier seed run's
     * or a hand-written one — is left exactly as it is: the first-write
     * channel is immutable after creation, so an existing row cannot be
     * converted into a system-seeded row after the fact.
     */
    @Transactional
    public Memory seed(String scopeSlug, MemoryType type, String key, String content) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("seed requires a non-blank key");
        }
        Scope scope = scopes.requireBySlug(scopeSlug);
        Optional<Memory> existing = find(SHARED_KEY_LOOKUP, scope, key).firstResultOptional();
        if (existing.isPresent()) {
            return existing.get();
        }
        return remember(SystemSubject.SENTINEL, scopeSlug, type, key, content, SourceChannel.SYSTEM);
    }

    /**
     * Recall memories. Returns:
     *   - Private scope: only the caller's own rows.
     *   - Shared scopes (project/global): all rows in that scope.
     *
     * When {@code scopeSlug} is null (an <em>unscoped</em> read) the shared
     * coverage depends on whether a {@code query} is given (D-CORE-5.1):
     * <ul>
     *   <li><b>no query</b> → caller's private rows + the <strong>global</strong>
     *       scope only; PROJECT scopes are NOT in the default view (D-CORE-5).</li>
     *   <li><b>with a non-blank query</b> → a discovery search across private +
     *       global + every PROJECT scope the caller sees by RLS, so a project
     *       memory is findable without naming its scope. Each hit carries its
     *       scope slug so the caller can pin it.</li>
     * </ul>
     * When {@code scopeSlug} is given the result is that scope (plus the global
     * scope when {@code includeGlobal} is true). RLS confines every case to the
     * caller's tenant and the private predicate to the caller's own rows.
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
        boolean hasQuery = query != null && !query.isBlank();
        // D-CORE-5.1: which shared kinds an unscoped read sees, in three cases:
        //   - scopeSlug == null, NO query  → GLOBAL only (the D-CORE-5 default
        //     digest view; PROJECT scopes surface only when asked for explicitly).
        //   - scopeSlug == null, WITH query → GLOBAL + PROJECT: a discovery search
        //     spans every project scope the caller sees by RLS, so a forgotten
        //     project memory is findable without naming its scope. Each hit
        //     carries its scope slug (MemoryDto.scope) so the assistant can pin it.
        //   - scopeSlug != null → GLOBAL + PROJECT: the PROJECT kind must stay in
        //     the set or a row in the requested project scope — matched below by
        //     `scope.slug = :scopeSlug` — would be filtered out by this predicate.
        // The private predicate above is UNCHANGED: a caller still sees only their
        // own private rows, and RLS still confines results to the caller's tenant.
        params.put("sharedKinds", scopeSlug == null && !hasQuery
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
        if (hasQuery) {
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
    public int forget(String callerSubject, String scopeSlug, UUID logicalId, String key) {
        Scope scope = scopes.requireBySlug(scopeSlug);

        // For private scope, restrict to caller's own rows.
        boolean isPrivate = scope.kind == ScopeKind.PRIVATE;

        try {
            if (logicalId != null) {
                // Address by logical_id (Amendment 3). Shared deletes are
                // author-independent (the canonical head); private restricts to
                // the caller's own row.
                String jpql = "logicalId = ?1 and scope = ?2" +
                              (isPrivate ? " and ownerSubject = ?3" : "");
                return isPrivate
                    ? (int) delete(jpql, logicalId, scope, callerSubject)
                    : (int) delete(jpql, logicalId, scope);
            }
            if (key != null) {
                // Code Finding 2 (ratified, Delta 4): forget-by-key is
                // author-independent for SHARED scopes (matching the shared
                // uniqueness + forget-by-id), and per-author for PRIVATE.
                return isPrivate
                    ? (int) delete("scope = ?1 and ownerSubject = ?2 and key = ?3",
                                   scope, callerSubject, key)
                    : (int) delete(SHARED_KEY_LOOKUP, scope, key);
            }
            throw new IllegalArgumentException("forget requires either id or key");
        } catch (PersistenceException pe) {
            // D-CORE-11: the BEFORE DELETE trigger raises with SQLSTATE P0001
            // when a protected row is in the delete-set. Translate that into
            // a typed exception so the MCP / admin layers can return a clean
            // 409 instead of a raw 500. Any other PSQLException re-raises.
            if (ProtectedDeleteBlockDetector.isProtectedDeleteBlock(pe)) {
                throw new ProtectedEntryException(
                    ProtectedEntryException.Reason.DELETE_BLOCKED,
                    key,
                    "delete blocked: row(s) carry protected = true (D-CORE-11). " +
                    "Protected system-seed mnemonics are structurally undeletable.");
            }
            throw pe;
        }
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
