package ai.kumbuka.admin;

import ai.kumbuka.repo.MemoryRepository.KeyExistsException;
import ai.kumbuka.repo.MemoryRepository.RemapPrivateForbiddenException;
import ai.kumbuka.repo.MemoryRepository.StaleVersionException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * map the memory create / remap exceptions to typed HTTP
 * 4xx with the {@code {code,message}} body the console consumes (reuses
 * {@link ScopeExceptionMappers#typed}) — never a bare 500. REST surface only;
 * the MCP {@code memory_remember} upsert path is untouched.
 */
@Provider
class KeyExistsExceptionMapper implements ExceptionMapper<KeyExistsException> {
    @Override
    public Response toResponse(KeyExistsException ex) {
        // 409 — the key is taken in the scope (author-independent); the curator
        // is offered a rename rather than a silent overwrite.
        return ScopeExceptionMappers.typed(Response.Status.CONFLICT, "KEY_EXISTS", ex.getMessage());
    }
}

@Provider
class RemapPrivateForbiddenExceptionMapper implements ExceptionMapper<RemapPrivateForbiddenException> {
    @Override
    public Response toResponse(RemapPrivateForbiddenException ex) {
        // 400 — private is structurally excluded as a remap endpoint (P1).
        return ScopeExceptionMappers.typed(
            Response.Status.BAD_REQUEST, "REMAP_PRIVATE_FORBIDDEN", ex.getMessage());
    }
}

@Provider
class StaleVersionExceptionMapper implements ExceptionMapper<StaleVersionException> {
    @Override
    public Response toResponse(StaleVersionException ex) {
        // 409 — optimistic lock: a concurrent edit advanced the version
        // under a stale writer; the console should reload and retry.
        return ScopeExceptionMappers.typed(Response.Status.CONFLICT, "STALE_VERSION", ex.getMessage());
    }
}
