package ai.kumbuka.mcp;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import ai.kumbuka.mcp.dto.Dtos;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.service.MemberWritePolicy;
import ai.kumbuka.service.WritePolicyResolver;
import ai.kumbuka.service.WritePolicyResolver.Resolved;
import ai.kumbuka.util.MemoryContentValidator;
import ai.kumbuka.util.ReferenceUrlValidator;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP tool surface. The five tools mandated by the spec, each identity-
 * aware: the acting subject comes from {@link SecurityIdentity} (Keycloak
 * {@code sub} claim from the bearer token validated by the `mcp` OIDC
 * tenant) and is never accepted as a tool argument.
 *
 * <p>{@code @Transactional} at class level guarantees each MCP tool runs
 * inside a JTA transaction so the {@code @TenantBound} interceptor can set
 * {@code app.tenant_id} via SET LOCAL (ADR-0011 §M6).
 */
@TenantBound
@Transactional
@ApplicationScoped
public class MemoryTools {

    /** Reserved slug of the one private scope per tenant (V1 unique index). */
    private static final String PRIVATE_SCOPE_SLUG = "private";

    @Inject SecurityIdentity identity;
    @Inject MemoryRepository memories;
    @Inject ScopeRepository scopes;
    @Inject MemoryConfig config;
    @Inject WritePolicyResolver policyResolver;
    @Inject MemberWritePolicy writePolicy;

    private String callerSubject() {
        String s = identity.getPrincipal().getName();
        if (s == null || s.isBlank()) {
            throw new IllegalStateException("no authenticated subject on /mcp request");
        }
        return s;
    }

    /**
     * Run client-input validation/parsing and, on a rejection, re-raise it as a
     * {@link ToolCallException}. The validators are shared with the admin REST
     * API where they throw {@link BadRequestException} (→ HTTP 400) or
     * {@link IllegalArgumentException} (bad enum/UUID); on the MCP surface those
     * are not {@code McpException}, so the framework would otherwise collapse
     * them into a bare {@code -32603 "Internal error"} with no message. Mapping
     * them to {@code ToolCallException} yields a proper tool error result
     * ({@code isError:true}) carrying the human-readable reason (e.g. "content
     * too long: max 1500 characters") so the assistant can correct and retry.
     */
    private static <T> T checkInput(java.util.function.Supplier<T> parseAndValidate) {
        try {
            return parseAndValidate.get();
        } catch (BadRequestException | IllegalArgumentException e) {
            throw new ToolCallException(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------

    @Tool(description =
        "Store a memory. Appends a new entry, or upserts an existing one if `key` is "
      + "provided and matches a prior entry with that key in the same scope (shared "
      + "scopes hold one canonical entry per key across authors; the private scope "
      + "keeps a per-author keyspace). "
      + "When `scope` is omitted, the team's writePolicy decides: 'ask' returns a "
      + "structured prompt asking which scope to use (no silent fallback to private); "
      + "'project' writes to the configured default project scope; 'global' writes to "
      + "the team-wide global scope. Use the explicit slug 'private' to write to the "
      + "caller's private space. Optionally attach a `reference` URL (where this came "
      + "from) — it is stored as metadata, never fetched, and kept out of the digest.")
    public Dtos.RememberResult memory_remember(
        @ToolArg(description = "The memory content (free text).")
            String content,
        @ToolArg(description = "Memory type: decision | convention | constraint | open_question | glossary | status.")
            String type,
        @ToolArg(description = "Scope slug. When omitted, follows the team's writePolicy.", required = false)
            String scope,
        @ToolArg(description = "Optional upsert key. Format: lowercase a-z + 0-9 with optional dot or hyphen separators (e.g. decision.d-ops-26). No underscores, no uppercase, no slashes — enforced server-side per E2E-06.", required = false)
            String key,
        @ToolArg(description = "Optional external provenance URL (http/https). Stored as metadata; never auto-fetched. Credential-bearing URLs are rejected.", required = false)
            String reference
    ) {
        // All client-input validation runs through checkInput so a rejection
        // surfaces as a clean MCP tool error (isError + reason), never a bare
        // -32603. Covers: type enum, content ≤1500 (F-1), key format (E2E-06),
        // reference URL.
        MemoryType t = checkInput(() -> {
            MemoryType parsed = MemoryType.fromDb(type);
            MemoryContentValidator.validate(content);
            ai.kumbuka.util.MemoryKeyValidator.validate(key);
            ReferenceUrlValidator.validate(reference);
            return parsed;
        });

        String scopeSlug = scope;
        if (scopeSlug == null) {
            // No explicit scope: consult writePolicy. Private is never the default
            // (D3 + handoff §F-2) — caller must opt in by passing 'private'.
            // The policy DTO is decision-bearing only on the prompt-for-scope return
            // (dogfood-11); on every successful write the real scope is in MemoryDto,
            // so resolve + attach the policy only here, never on explicit-scope writes.
            Resolved policy = policyResolver.resolve();
            switch (policy.effective()) {
                case ASK -> {
                    String reason = switch (policy.defaultScopeStatus()) {
                        case OK       -> "the team's write policy is 'ask' — please specify a scope";
                        case MISSING  -> "the team's write policy is 'project' but no default scope is set — please specify a scope";
                        case ARCHIVED -> "the team's default scope is archived — please specify a scope";
                        case INVALID  -> "the team's default scope is no longer a project — please specify a scope";
                    };
                    return new Dtos.RememberResult(null, false, prompt(reason), toDto(policy));
                }
                case PROJECT -> scopeSlug = policy.defaultScopeSlug();
                case GLOBAL  -> scopeSlug = "global";
            }
        }

        // D-CORE-2: a muted member keeps their private scope but loses shared
        // writes. There is exactly one private scope per tenant, reserved slug
        // "private" (V1, unique index) — any other slug is shared.
        if (!PRIVATE_SCOPE_SLUG.equals(scopeSlug)) {
            writePolicy.assertCanWriteShared(callerSubject());
        }

        // Tenant isolation is enforced by Hibernate's @TenantId discriminator on
        // every query — never bind tenant_id by hand here. This `existed` probe
        // MUST mirror MemoryRepository.remember's scope-kind-differentiated
        // upsert lookup (A1.3 (1)) or the DTO would report existed=false for a
        // shared-key write the repo then upserts: SHARED is author-independent
        // (scope, key); PRIVATE is per-author (scope, owner, key). Private is the
        // reserved slug "private" (V1, unique index) — any other slug is shared.
        final boolean existed;
        if (key == null) {
            existed = false;
        } else if (PRIVATE_SCOPE_SLUG.equals(scopeSlug)) {
            existed = memories.find(
                "scope.slug = ?1 and ownerSubject = ?2 and key = ?3",
                scopeSlug, callerSubject(), key).firstResultOptional().isPresent();
        } else {
            existed = memories.find(
                "scope.slug = ?1 and key = ?2",
                scopeSlug, key).firstResultOptional().isPresent();
        }

        final Memory m;
        try {
            m = memories.remember(callerSubject(), scopeSlug, t, key, content, SourceChannel.MCP);
        } catch (ai.kumbuka.repo.ProtectedEntryException pex) {
            // D-CORE-11: a protected system-seed entry already owns this key.
            // Return a typed structured error instead of a -32603 "Internal error".
            return new Dtos.RememberResult(null, false, null, null,
                new Dtos.ProtectedError("PROTECTED_UPSERT_BLOCKED", pex.key(), pex.getMessage()));
        } catch (ai.kumbuka.repo.ScopeRepository.ScopeNotFoundException snf) {
            // dogfood-14: an unknown or RLS-invisible scope slug surfaces as a typed
            // tool error (isError + reason), not a bare -32603 — the same MCP
            // boundary typing as the #60 content-length fix. requireBySlug fires
            // before any insert, so no ghost scope/entry is created. Scopes are
            // never auto-created here (D-CORE-14: provisioning/KC-Org only).
            throw new ToolCallException("scope '" + scopeSlug + "' does not exist or is not visible");
        } catch (ai.kumbuka.repo.MemoryRepository.StaleVersionException sve) {
            // §A1.6 optimistic lock: a concurrent edit advanced the version under
            // this stale write — surface a typed tool error (isError + reason),
            // not a bare -32603. The client should reload and retry.
            throw new ToolCallException(sve.getMessage());
        }
        // D-CORE-7: attach the provenance URL on a freshly-written row only — an
        // upsert preserves the row's original reference. Validated above; blank = none.
        if (!existed && reference != null && !reference.isBlank()) {
            m.reference = reference;
        }
        return new Dtos.RememberResult(Dtos.MemoryDto.from(m), existed, null, null, null);
    }

    private Dtos.PromptForScope prompt(String reason) {
        List<Dtos.ScopeDto> visible = scopes.listAll().stream()
            .filter(s -> s.kind != ScopeKind.PRIVATE)   // private is offered as an explicit value, not in the list
            .map(Dtos.ScopeDto::from)
            .toList();
        return new Dtos.PromptForScope(reason, visible);
    }

    private Dtos.EffectiveWritePolicy toDto(Resolved r) {
        return new Dtos.EffectiveWritePolicy(
            r.stored().dbValue(),
            r.effective().dbValue(),
            r.defaultScopeSlug(),
            r.defaultScopeStatus().name().toLowerCase()
        );
    }

    // -----------------------------------------------------------------------

    @Tool(description =
        "Recall memories. With no `scope`, returns the caller's private memories plus the "
      + "team-wide global scope ONLY — project scopes are not included by default; ask for a "
      + "project explicitly by passing its `scope` slug to see its memories. Filters: scope, "
      + "type, and a simple substring `query` over content. `include_global` adds the global "
      + "scope on top when a specific non-global `scope` is requested.")
    public Dtos.RecallResult memory_recall(
        @ToolArg(description = "Restrict to this scope slug.", required = false) String scope,
        @ToolArg(description = "Restrict to this memory type.", required = false) String type,
        @ToolArg(description = "Substring match (case-insensitive) on content.", required = false) String query,
        @ToolArg(description = "When a scope is given, also include the global scope. Default false.", required = false) Boolean include_global
    ) {
        MemoryType t = checkInput(() -> type == null ? null : MemoryType.fromDb(type));
        boolean inclGlobal = include_global != null && include_global;
        List<Memory> rows = memories.recall(callerSubject(), scope, t, query, inclGlobal);
        return Dtos.RecallResult.of(rows);
    }

    // -----------------------------------------------------------------------

    @Tool(description =
        "Delete a memory. Identify it by `id` (UUID) or by (`scope`, `key`). "
      + "Private memories can only be deleted by their owner; shared-scope deletes "
      + "only remove the caller's own keyed entry, never another author's row.")
    public Dtos.ForgetResult memory_forget(
        @ToolArg(description = "Scope slug.") String scope,
        @ToolArg(description = "Memory id (UUID).", required = false) String id,
        @ToolArg(description = "Upsert key, if the entry was written with one.", required = false) String key
    ) {
        UUID uuid = checkInput(() -> (id == null || id.isBlank()) ? null : UUID.fromString(id));
        // D-CORE-2: shared forget is a write — suspended for muted members; a
        // muted member can still forget in their own private scope (slug "private").
        if (!PRIVATE_SCOPE_SLUG.equals(scope)) {
            writePolicy.assertCanWriteShared(callerSubject());
        }
        final int n;
        try {
            n = memories.forget(callerSubject(), scope, uuid, key);
        } catch (ai.kumbuka.repo.ProtectedEntryException pex) {
            // D-CORE-11: caller tried to delete a protected system-seed entry.
            // The structural trigger (memory_protected_delete_block) raised the
            // exception; we translate to a typed result for the MCP client.
            return new Dtos.ForgetResult(0,
                new Dtos.ProtectedError("PROTECTED_DELETE_BLOCKED", pex.key(), pex.getMessage()));
        } catch (ai.kumbuka.repo.ScopeRepository.ScopeNotFoundException snf) {
            // dogfood-14: unknown/invisible scope → typed tool error, not -32603.
            throw new ToolCallException("scope '" + scope + "' does not exist or is not visible");
        }
        return new Dtos.ForgetResult(n);
    }

    // -----------------------------------------------------------------------

    @Tool(description =
        "List scopes visible to the caller: their own private scope plus every shared "
      + "(project + global) scope on this team.")
    public Dtos.ScopesResult memory_scopes() {
        List<Scope> all = scopes.listAll();
        return new Dtos.ScopesResult(all.stream().map(Dtos.ScopeDto::from).toList());
    }

    // -----------------------------------------------------------------------

    @Tool(description =
        "Return a typed digest of memories: grouped by type, capped per group. Useful for "
      + "loading context at conversation start. By DEFAULT the digest returns the steering "
      + "types only (decision, constraint, convention, glossary, status) and EXCLUDES "
      + "open_question, so it isn't dominated by unresolved questions. Pass `types` "
      + "(comma-separated, e.g. \"decision,open_question\") to digest an explicit set — for "
      + "example to review what is open on a topic. With no `scope`, digests the caller's "
      + "private memories plus the global scope ONLY (project scopes are excluded by "
      + "default); pass a project `scope` slug to digest that project.")
    public Dtos.LoadContextResult memory_load_context(
        @ToolArg(description = "Optional scope slug. Omit to digest private + global only; pass a project slug to digest that project.", required = false) String scope,
        @ToolArg(description = "Optional comma-separated memory types to include (decision, constraint, convention, glossary, open_question, status). Omit for the steering-types default (which excludes open_question).", required = false) String types
    ) {
        java.util.Set<MemoryType> wanted = checkInput(() -> parseTypes(types));
        Map<MemoryType, List<Memory>> grouped = memories.loadContext(callerSubject(), scope, wanted);
        Map<String, List<Dtos.MemoryDto>> byType = new java.util.LinkedHashMap<>();
        int total = 0;
        // Deterministic ordering; only the types actually digested are emitted as keys
        // (so the steering default carries no empty open_question bucket — D-CORE-6).
        for (MemoryType t : new MemoryType[]{
                MemoryType.DECISION,
                MemoryType.CONSTRAINT,
                MemoryType.CONVENTION,
                MemoryType.GLOSSARY,
                MemoryType.OPEN_QUESTION,
                MemoryType.STATUS}) {
            if (!grouped.containsKey(t)) {
                continue;
            }
            // forDigest omits the reference URL — the digest stays lean (D-CORE-7).
            List<Dtos.MemoryDto> dtos = grouped.get(t).stream().map(Dtos.MemoryDto::forDigest).toList();
            byType.put(t.dbValue(), dtos);
            total += dtos.size();
        }
        return new Dtos.LoadContextResult(byType, total);
    }

    /**
     * Parse the optional comma-separated {@code types} arg into a type set; null/blank →
     * null, which the repository reads as the steering-types default (D-CORE-6). An
     * unknown type name raises a clear error back to the caller.
     */
    private static java.util.Set<MemoryType> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return null;
        }
        java.util.Set<MemoryType> out = java.util.EnumSet.noneOf(MemoryType.class);
        for (String part : types.split(",")) {
            String v = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (!v.isEmpty()) {
                out.add(MemoryType.fromDb(v));
            }
        }
        return out.isEmpty() ? null : out;
    }
}
