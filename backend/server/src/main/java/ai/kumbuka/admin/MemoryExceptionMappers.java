package ai.kumbuka.admin;

import ai.kumbuka.repo.MemoryRepository.KeyExistsException;
import ai.kumbuka.repo.MemoryRepository.RemapPrivateForbiddenException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * D-CORE-16 / D-CORE-17: map the memory create / remap exceptions to typed HTTP
 * 4xx with the {@code {code,message}} body the console consumes (reuses
 * {@link ScopeExceptionMappers#typed}) — never a bare 500. REST surface only;
 * the MCP {@code memory_remember} upsert path is untouched.
 */
@Provider
class KeyExistsExceptionMapper implements ExceptionMapper<KeyExistsException> {
    @Override
    public Response toResponse(KeyExistsException ex) {
        // 409 — the key is taken in the scope (author-independent); the curator
        // is offered a rename rather than a silent overwrite (dogfood-21).
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
