package ai.kumbuka.service;

/**
 * a mutating operation was rejected because its target
 * scope carries the {@code scope.locked} content-lock flag (read-only).
 *
 * <p>Thrown by {@link MemberWritePolicy#assertScopeWritable} as a pre-check,
 * before any row is created, updated, deleted, or re-homed. Distinct from
 * {@link ai.kumbuka.repo.ScopeRepository.ScopeFixedException} (the
 * {@code scope.fixed} existence/identity axis) and from
 * {@link ai.kumbuka.repo.ProtectedEntryException} (the per-entry
 * {@code memory.lock} axis) — the three axes are orthogonal and
 * compose freely.
 *
 * <p>Surface mapping:
 * <ul>
 *   <li>console (admin REST) → HTTP 409 {@code SCOPE_READ_ONLY}
 *       (see {@code ScopeReadOnlyExceptionMapper});</li>
 *   <li>MCP → a typed tool error (structured {@code SCOPE_READ_ONLY} carrier),
 *       mirroring how {@link ai.kumbuka.repo.ProtectedEntryException} surfaces.</li>
 * </ul>
 *
 * <p>The HTTP code uses the ratified UI vocabulary "read-only"; the DB column
 * stays named {@code locked}.
 */
public class ScopeReadOnlyException extends RuntimeException {

    /** The slug of the locked scope whose write was rejected. */
    private final String scope;

    public ScopeReadOnlyException(String scope, String message) {
        super(message);
        this.scope = scope;
    }

    public String scope() { return scope; }
}
