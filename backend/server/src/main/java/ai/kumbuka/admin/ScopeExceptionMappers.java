package ai.kumbuka.admin;

import ai.kumbuka.repo.ScopeRepository.ScopeAlreadyExistsException;
import ai.kumbuka.repo.ScopeRepository.ScopeLockedException;
import ai.kumbuka.repo.ScopeRepository.ScopeNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * dogfood-19: map scope-lifecycle exceptions to typed HTTP 4xx with the
 * {@code {code,message}} body the team console's {@code mapApiError} already
 * consumes — so a duplicate / unknown / locked scope surfaces as a clean error
 * instead of a bare 500 (which the console redacts into a Server-Components
 * crash). Body shape parallels {@link ProtectedEntryExceptionMapper}.
 *
 * <p>REST surface only. The MCP path maps {@link ScopeNotFoundException} to a
 * {@code ToolCallException} inside {@code MemoryTools} (#63); these JAX-RS
 * mappers never reach that path and do not regress it.
 */
final class ScopeExceptionMappers {
    private ScopeExceptionMappers() {}

    static Response typed(Response.Status status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}

@Provider
class ScopeAlreadyExistsExceptionMapper implements ExceptionMapper<ScopeAlreadyExistsException> {
    @Override
    public Response toResponse(ScopeAlreadyExistsException ex) {
        // 409 — the slug is taken (possibly by an ARCHIVED scope; the message
        // carries the slug so the console can hint at the archived collision).
        return ScopeExceptionMappers.typed(Response.Status.CONFLICT, "SCOPE_EXISTS", ex.getMessage());
    }
}

@Provider
class ScopeNotFoundExceptionMapper implements ExceptionMapper<ScopeNotFoundException> {
    @Override
    public Response toResponse(ScopeNotFoundException ex) {
        return ScopeExceptionMappers.typed(Response.Status.NOT_FOUND, "SCOPE_NOT_FOUND", ex.getMessage());
    }
}

@Provider
class ScopeLockedExceptionMapper implements ExceptionMapper<ScopeLockedException> {
    @Override
    public Response toResponse(ScopeLockedException ex) {
        return ScopeExceptionMappers.typed(Response.Status.CONFLICT, "SCOPE_LOCKED", ex.getMessage());
    }
}
