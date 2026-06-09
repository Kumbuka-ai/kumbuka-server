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
 * Internal server-to-server endpoint that drops every OSS-side row for
 * a fully-purged tenant. Called by the ops-console's 30-day purge cron
 * after each member has been erased and the tenant connector dropped.
 *
 * <h3>Contract</h3>
 *
 * <p>Same security shape as {@link EraseSubjectResource}: shared-secret
 * bearer token via {@code kumbuka.internal.erasure.token}, misroute
 * guard against the resolver's current tenant. Returns per-table
 * delete counts only — never content.
 *
 * <h3>When to call</h3>
 *
 * <p>After all members of the tenant have been erased via
 * {@code /api/internal/erase-subject}. The OSS-side erase removes
 * private memory + tombstones shared authorship; this endpoint
 * subsequently drops the tombstoned-shared rows + scopes + team_settings
 * + the team row itself, so the tenant leaves no orphans on disk.
 *
 * <p>Safe to invoke against a tenant that still has members — counts
 * surface what was actually removed so the operator can spot a partial
 * purge.
 */
@Path("/api/internal/purge-tenant")
@PermitAll
@TenantBound
public class PurgeTenantResource {

    /** Error-envelope keys reused across the refusal branches. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    @Inject ErasureConfig config;
    @Inject TenantResolver resolver;
    @Inject TenantDataPurgeService purger;

    public record PurgeRequest(UUID tenantId) {}

    public record PurgeResponse(
        int memoryDeleted,
        int userAccountsDeleted,
        int teamSettingsDeleted,
        int scopesDeleted,
        int teamDeleted) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response purge(
            @HeaderParam("Authorization") String authHeader,
            PurgeRequest req) {
        final String configured = config.token().orElse("").trim();
        if (configured.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                    KEY_ERROR, "purge_endpoint_not_configured",
                    KEY_MESSAGE, "kumbuka.internal.erasure.token is unset"))
                .build();
        }
        if (authHeader == null || !authHeader.equals("Bearer " + configured)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of(KEY_ERROR, "unauthorized"))
                .build();
        }
        if (req == null || req.tenantId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    KEY_ERROR, "bad_request",
                    KEY_MESSAGE, "tenantId is required"))
                .build();
        }

        final UUID resolvedTenant = resolver.currentTenant();
        if (!req.tenantId().equals(resolvedTenant)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    KEY_ERROR, "tenant_mismatch",
                    KEY_MESSAGE, "request tenant does not match this server's tenant"))
                .build();
        }

        final TenantDataPurgeService.PurgeResult out =
            purger.purgeTenant(req.tenantId().toString());
        return Response.ok(new PurgeResponse(
            out.memoryDeleted(),
            out.userAccountsDeleted(),
            out.teamSettingsDeleted(),
            out.scopesDeleted(),
            out.teamDeleted()))
            .build();
    }
}
