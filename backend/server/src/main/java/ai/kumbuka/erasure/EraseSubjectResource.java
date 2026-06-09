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
 * Internal server-to-server endpoint that discharges the OSS side of the
 * member-erasure orchestration (ADR-0015). Called by the kumbuka-ai
 * provider's {@code OssBackendErasureClient}, never by humans, never by the
 * MCP or admin pipelines.
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
 * the {@code member.erase} row). The OSS side returns counts only — no
 * content, no subjects — and the provider records the outcome.
 */
@Path("/api/internal/erase-subject")
@PermitAll
@TenantBound
public class EraseSubjectResource {

    @Inject ErasureConfig config;
    @Inject TenantResolver resolver;
    @Inject MemberErasureService erasure;

    public record EraseRequest(UUID tenantId, String subject) {}

    public record EraseResponse(int privatePurged, int sharedTombstoned, int scopesTombstoned) {}

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
                    "error", "erase_endpoint_not_configured",
                    "message", "kumbuka.internal.erasure.token is unset"))
                .build();
        }
        if (authHeader == null || !authHeader.equals("Bearer " + configured)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized"))
                .build();
        }
        if (req == null || req.tenantId() == null
                || req.subject() == null || req.subject().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    "error", "bad_request",
                    "message", "tenantId and subject are required"))
                .build();
        }

        final UUID resolvedTenant = resolver.currentTenant();
        if (!req.tenantId().equals(resolvedTenant)) {
            // Misrouted call: the provider asked us to erase someone in a
            // tenant that isn't ours. Refuse rather than silently scoping
            // to our own tenant.
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    "error", "tenant_mismatch",
                    "message", "request tenant does not match this server's tenant"))
                .build();
        }

        final MemberErasureService.EraseResult out = erasure.eraseSubject(req.subject());
        return Response.ok(new EraseResponse(
            out.privatePurged(), out.sharedTombstoned(), out.scopesTombstoned()))
            .build();
    }
}
