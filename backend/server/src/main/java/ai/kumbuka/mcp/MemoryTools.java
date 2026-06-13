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
import ai.kumbuka.util.ReferenceUrlValidator;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP tool surface. The five tools mandated by the spec, each identity-
 * aware: the acting subject comes from {@link SecurityIdentity} (Keycloak
 * {@code sub} claim from the bearer token validated by the `mcp` OIDC
 * tenant) and is never accepted as a tool argument.
 */
/**
 * @Transactional at class level guarantees each MCP tool runs inside a
 * JTA transaction so the @TenantBound interceptor can set app.tenant_id
 * via SET LOCAL (ADR-0011 §M6).
 */
@TenantBound
@Transactional
@ApplicationScoped
public class MemoryTools {

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

    // -----------------------------------------------------------------------

    @Tool(description =
        "Store a memory. Appends a new entry, or upserts an existing one if `key` is "
      + "provided and matches a prior entry by this author in the same scope. "
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
        @ToolArg(description = "Optional upsert key (lowercase, dot/kebab-namespaced).", required = false)
            String key,
        @ToolArg(description = "Optional external provenance URL (http/https). Stored as metadata; never auto-fetched. Credential-bearing URLs are rejected.", required = false)
            String reference
    ) {
        MemoryType t = MemoryType.fromDb(type);
        ReferenceUrlValidator.validate(reference);
        Resolved policy = policyResolver.resolve();
        Dtos.EffectiveWritePolicy policyDto = toDto(policy);

        String scopeSlug = scope;
        if (scopeSlug == null) {
            // No explicit scope: consult writePolicy. Private is never the default
            // (D3 + handoff §F-2) — caller must opt in by passing 'private'.
            switch (policy.effective()) {
                case ASK -> {
                    String reason = switch (policy.defaultScopeStatus()) {
                        case OK       -> "the team's write policy is 'ask' — please specify a scope";
                        case MISSING  -> "the team's write policy is 'project' but no default scope is set — please specify a scope";
                        case ARCHIVED -> "the team's default scope is archived — please specify a scope";
                        case INVALID  -> "the team's default scope is no longer a project — please specify a scope";
                    };
                    return new Dtos.RememberResult(null, false, prompt(reason), policyDto);
                }
                case PROJECT -> scopeSlug = policy.defaultScopeSlug();
                case GLOBAL  -> scopeSlug = "global";
            }
        }

        // D-CORE-2: a muted member keeps their private scope but loses shared
        // writes. There is exactly one private scope per tenant, reserved slug
        // "private" (V1, unique index) — any other slug is shared.
        if (!"private".equals(scopeSlug)) {
            writePolicy.assertCanWriteShared(callerSubject());
        }

        // Tenant isolation is enforced by Hibernate's @TenantId discriminator on
        // every query — never bind tenant_id by hand here. The old explicit
        // `tenantId = ?1` predicate bound config.tenantId() (a UUID) to the
        // String-typed discriminator field → QueryArgumentException on every
        // keyed write; and in SaaS config.tenantId() is the zero sentinel, not
        // the request tenant, so it would never match even with the right type.
        // (Same predicate shape MemoryRepository.remember uses for its upsert.)
        boolean existed = key != null && memories.find(
            "scope.slug = ?1 and ownerSubject = ?2 and key = ?3",
            scopeSlug, callerSubject(), key
        ).firstResultOptional().isPresent();

        Memory m = memories.remember(callerSubject(), scopeSlug, t, key, content, SourceChannel.MCP);
        // D-CORE-7: attach the provenance URL on a freshly-written row only — an
        // upsert preserves the row's original reference. Validated above; blank = none.
        if (!existed && reference != null && !reference.isBlank()) {
            m.reference = reference;
        }
        return new Dtos.RememberResult(Dtos.MemoryDto.from(m), existed, null, policyDto);
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
        MemoryType t = type == null ? null : MemoryType.fromDb(type);
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
        UUID uuid = (id == null || id.isBlank()) ? null : UUID.fromString(id);
        // D-CORE-2: shared forget is a write — suspended for muted members; a
        // muted member can still forget in their own private scope (slug "private").
        if (!"private".equals(scope)) {
            writePolicy.assertCanWriteShared(callerSubject());
        }
        int n = memories.forget(callerSubject(), scope, uuid, key);
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
        java.util.Set<MemoryType> wanted = parseTypes(types);
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
