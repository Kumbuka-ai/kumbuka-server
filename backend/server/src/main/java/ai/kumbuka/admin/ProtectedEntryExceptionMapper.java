package ai.kumbuka.admin;

import ai.kumbuka.repo.ProtectedEntryException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translate {@link ProtectedEntryException} into a clean
 * HTTP 409 with a typed error body for admin REST callers — so a console
 * write or delete that hits a protected system-seed row surfaces as a
 * structured error instead of a 500.
 *
 * Body shape:
 * <pre>{@code
 *   { "code": "PROTECTED_<reason>",   // e.g. PROTECTED_RESERVED_NAMESPACE
 *     "key":  "<key-or-null>",
 *     "message": "<human-readable>" }
 * }</pre>
 *
 * The code is derived generically from the reason name, so a new reason needs no
 * change here. Parallel to the MCP-side structured error in {@code
 * Dtos.ProtectedError} — same code values so the team console can pattern-match
 * either surface with one handler.
 */
@Provider
public class ProtectedEntryExceptionMapper implements ExceptionMapper<ProtectedEntryException> {

    @Override
    public Response toResponse(ProtectedEntryException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "PROTECTED_" + ex.reason().name());
        body.put("key", ex.key());
        body.put("message", ex.getMessage());
        return Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON)
            .entity(body)
            .build();
    }
}
