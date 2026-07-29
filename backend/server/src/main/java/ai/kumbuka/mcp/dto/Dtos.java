package ai.kumbuka.mcp.dto;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured tool-return shapes. The MCP framework serializes these to
 * {@code structuredContent} per the MCP spec — Claude can parse fields
 * reliably without a separate text-parse pass.
 */
public final class Dtos {

    private Dtos() {}

    public record MemoryDto(
        UUID logicalId,       // the entry's reference identity
        String scope,         // slug — the addressable identity (ADR-0007)
        String type,
        String key,
        String content,
        String reference,     // external provenance URL (null in the digest)
        String author,        // Keycloak `sub` of the row's owner
        String source,        // 'console' or 'mcp' (ADR-0008)
        Instant createdAt,
        Instant updatedAt
    ) {
        public static MemoryDto from(Memory m) {
            return new MemoryDto(
                m.logicalId,
                m.scope.slug,
                m.type.dbValue(),
                m.key,
                m.content,
                m.reference,
                m.ownerSubject,
                m.source.dbValue(),
                m.createdAt,
                m.updatedAt
            );
        }

        /**
         * Digest projection — omits the {@code reference} URL (guard 2:
         * verify-on-demand). Used by {@code memory_load_context} so the bulk
         * context load stays lean and carries no external pointers.
         */
        public static MemoryDto forDigest(Memory m) {
            return new MemoryDto(
                m.logicalId,
                m.scope.slug,
                m.type.dbValue(),
                m.key,
                m.content,
                null,
                m.ownerSubject,
                m.source.dbValue(),
                m.createdAt,
                m.updatedAt
            );
        }
    }

    public record ScopeDto(String slug, String name, String kind, boolean fixed, boolean archived) {
        public static ScopeDto from(Scope s) {
            return new ScopeDto(s.slug, s.name, s.kind.dbValue(),
                                Boolean.TRUE.equals(s.fixed),
                                Boolean.TRUE.equals(s.archived));
        }
    }

    /**
     * Either persists the entry (memory is non-null) or asks the caller to
     * pick a scope (prompt is non-null). The {@code prompt} fires when no
     * scope was supplied and the team's effective writePolicy is ASK — the
     * tool refuses to silently fall back to the user's private scope
     * Exactly one of memory / prompt is non-null.
     */
    public record RememberResult(
        MemoryDto memory,
        boolean upserted,
        PromptForScope prompt,
        EffectiveWritePolicy policy,
        ProtectedError error
    ) {
        /** Convenience: legacy four-arg constructor — no protected-error path hit. */
        public RememberResult(MemoryDto memory, boolean upserted,
                              PromptForScope prompt, EffectiveWritePolicy policy) {
            this(memory, upserted, prompt, policy, null);
        }
    }

    /**
     * Surfaced when a non-system caller's write or delete would touch a protected
     * row, or address a key in the reserved namespace. Carries the
     * typed code so the client doesn't have to pattern-match a string message.
     */
    public record ProtectedError(
        String code,    // PROTECTED_<reason> (e.g. PROTECTED_RESERVED_NAMESPACE) or SCOPE_READ_ONLY
        String key,
        String message
    ) {}

    public record PromptForScope(
        String reason,
        List<ScopeDto> available
    ) {}

    public record EffectiveWritePolicy(
        String stored,            // ask | project | global
        String effective,         // ditto; differs from `stored` when fallback applied
        String defaultScopeSlug,  // nullable
        String defaultScopeStatus // ok | missing | archived | invalid
    ) {}

    public record RecallResult(List<MemoryDto> memories, int count) {
        public static RecallResult of(List<Memory> rows) {
            List<MemoryDto> dtos = rows.stream().map(MemoryDto::from).toList();
            return new RecallResult(dtos, dtos.size());
        }
    }

    public record ForgetResult(int deleted, ProtectedError error) {
        /** Convenience: legacy single-arg constructor — no protected-block path hit. */
        public ForgetResult(int deleted) { this(deleted, null); }
    }

    /**
     * Revision result. On success {@code memory} is the revised entry; on a
     * protected / locked / reserved-namespace rejection {@code error} carries the
     * typed reason. An absent target, a bad address, or an optimistic-lock
     * conflict surface as tool errors (isError), not as a field here — exactly
     * one of {@code memory} / {@code error} is non-null.
     */
    public record UpdateResult(MemoryDto memory, ProtectedError error) {
        /** Convenience: the success path — no typed error. */
        public UpdateResult(MemoryDto memory) { this(memory, null); }
    }

    public record ScopesResult(List<ScopeDto> scopes) {}

    public record LoadContextResult(
        Map<String, List<MemoryDto>> byType,
        int total
    ) {}
}
