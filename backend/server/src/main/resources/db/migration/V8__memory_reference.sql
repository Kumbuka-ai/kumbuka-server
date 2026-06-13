-- ===========================================================================
-- V8: optional external provenance URL on a memory (D-CORE-7).
--
-- A single optional pointer to where a memory came from — structured METADATA,
-- never folded into the ≤1500-char content body. Verify-on-demand: it is
-- omitted from the load_context digest and NEVER auto-fetched on read (that
-- would be an SSRF / prompt-injection vector; see P-1). Governed by
-- `convention.mnemonic-lean-primitive` — exactly one pointer field, no more.
--
-- Additive + nullable; RLS on `memory` is unaffected. The CHECK is
-- defence-in-depth (the application ReferenceUrlValidator is the primary gate):
-- reject credential-bearing URLs — basic-auth `scheme://user:pass@host` and the
-- common secret query params — because they are secrets and collide with the
-- never-store rule, especially in shared scopes.
-- ===========================================================================

ALTER TABLE memory ADD COLUMN reference TEXT;

ALTER TABLE memory ADD CONSTRAINT memory_reference_no_credentials CHECK (
    reference IS NULL
    OR (
        reference !~* '^[a-z][a-z0-9+.-]*://[^/@[:space:]]*@'
        AND reference !~* '[?&](token|password|passwd|secret|api[_-]?key|access[_-]?token)='
    )
);
