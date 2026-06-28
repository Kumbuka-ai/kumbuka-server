package ai.kumbuka.admin;

import ai.kumbuka.repo.ScopeRepository.ScopeAlreadyExistsException;
import ai.kumbuka.repo.ScopeRepository.ScopeFixedException;
import ai.kumbuka.repo.ScopeRepository.ScopeNotFoundException;
import ai.kumbuka.service.ScopeReadOnlyException;
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
class ScopeFixedExceptionMapper implements ExceptionMapper<ScopeFixedException> {
    @Override
    public Response toResponse(ScopeFixedException ex) {
        return ScopeExceptionMappers.typed(Response.Status.CONFLICT, "SCOPE_FIXED", ex.getMessage());
    }
}

/**
 * FEAT-19 / D-CORE-18: a member's mutation on a content-locked
 * ({@code scope.locked}) scope. 409 with the {@code SCOPE_READ_ONLY} code the
 * console's {@code mapApiError} consumes. Distinct from {@code SCOPE_FIXED}
 * (the existence/identity axis) — the retired fixed-scope code is never recycled
 * for the content-lock rejection (D-CORE-18).
 */
@Provider
class ScopeReadOnlyExceptionMapper implements ExceptionMapper<ScopeReadOnlyException> {
    @Override
    public Response toResponse(ScopeReadOnlyException ex) {
        return ScopeExceptionMappers.typed(Response.Status.CONFLICT, "SCOPE_READ_ONLY", ex.getMessage());
    }
}
