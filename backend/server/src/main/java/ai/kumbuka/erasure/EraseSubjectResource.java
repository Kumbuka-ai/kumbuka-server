package ai.kumbuka.erasure;

import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.tenancy.TenantResolver;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.UUID;

/**
 * Internal server-to-server endpoint for the OSS side of the member-erasure
 * orchestration (ADR-0015). Called by the kumbuka-ai provider's
 * {@code OssBackendErasureClient}, never by humans, never by the MCP or admin
 * pipelines.
 *
 * <p><strong>It refuses every call while the erasure path across the service
 * boundary is missing</strong> — see "What this endpoint does today" below.
 *
 * <h3>Security model</h3>
 *
 * <p>The endpoint is {@code @PermitAll} — it bypasses the Keycloak/OIDC
 * pipelines deliberately, because the caller (the provider) does not hold
 * a tenant user token. Authentication is a <strong>shared-secret bearer
 * token</strong> configured via {@code kumbuka.internal.erasure.token}
 * (see {@link ErasureConfig}).
 *
 * <p>The validation is constant-time-ish (single string equality on a
 * short identifier) — there's no user enumeration to time, the secret is
 * a single value rotated as a unit. When the token is unset on the host
 * the endpoint returns <strong>503</strong> rather than 401, so an
 * unconfigured deploy fails loud instead of silently accepting any caller.
 *
 * <h3>Tenant scoping</h3>
 *
 * <p>The body declares the tenant being operated on. In the OSS edition
 * the {@link TenantResolver} returns the singleton tenant; we
 * <strong>validate the body's {@code tenantId} matches</strong>, so a
 * misrouted provider call against an unrelated deployment is refused with
 * 400 instead of silently mutating data. The {@link TenantBound}
 * annotation pins the Postgres {@code app.tenant_id} GUC for the duration
 * of the transaction.
 *
 * <h3>Audit</h3>
 *
 * <p>The provider holds the audit trail (ADR-0015 §C: the provider writes
 * the {@code member.erase} row). The OSS side never returns content or
 * subjects; while it refuses, it returns no counts either, and the provider
 * records nothing because nothing happened.
 *
 * <h3>What this endpoint does today: it refuses</h3>
 *
 * <p>Every call that gets past the checks below is refused with
 * {@link ErasurePathIncomplete}, before anything changes. The reason is that
 * the erasure this endpoint is asked for cannot be carried out: the content
 * half of the policy left with the memory engine, the path that would ask the
 * memory service for its share does not exist yet, and running the core's own
 * share alone would leave a member half erased with no participant holding a
 * record that says so.
 *
 * <p>{@link MemberErasureService} is that share, and it keeps its tests. In
 * this state it has no caller: the conductor of the erasure path is what will
 * call it, and the same commission is what removes the refusal.
 */
@Path("/api/internal/erase-subject")
@PermitAll
@TenantBound
public class EraseSubjectResource {

    /** Error-envelope keys reused across the refusal branches. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    @Inject ErasureConfig config;
    @Inject TenantResolver resolver;
    @Inject MemberErasureService erasure;

    public record EraseRequest(UUID tenantId, String subject) {}

    public record EraseResponse(int scopesTombstoned) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response erase(
            @HeaderParam("Authorization") String authHeader,
            EraseRequest req) {
        final String configured = config.token().orElse("").trim();
        if (configured.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                    KEY_ERROR, "erase_endpoint_not_configured",
                    KEY_MESSAGE, "kumbuka.internal.erasure.token is unset"))
                .build();
        }
        if (authHeader == null || !authHeader.equals("Bearer " + configured)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of(KEY_ERROR, "unauthorized"))
                .build();
        }
        if (req == null || req.tenantId() == null
                || req.subject() == null || req.subject().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    KEY_ERROR, "bad_request",
                    KEY_MESSAGE, "tenantId and subject are required"))
                .build();
        }

        final UUID resolvedTenant = resolver.currentTenant();
        if (!req.tenantId().equals(resolvedTenant)) {
            // Misrouted call: the provider asked us to erase someone in a
            // tenant that isn't ours. Refuse rather than silently scoping
            // to our own tenant.
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    KEY_ERROR, "tenant_mismatch",
                    KEY_MESSAGE, "request tenant does not match this server's tenant"))
                .build();
        }

        // Last, and after every check that was here before it: the erasure
        // path across the service boundary does not exist, so this call is
        // refused before the core's own share runs. Placed here so a caller
        // who does not hold the shared secret still learns nothing new — the
        // 401 and the two 400s answer first, exactly as they did.
        return ErasurePathIncomplete.refuse(
            "/api/internal/erase-subject", resolvedTenant, ErasurePathIncomplete.ERASE_MESSAGE);
    }
}
