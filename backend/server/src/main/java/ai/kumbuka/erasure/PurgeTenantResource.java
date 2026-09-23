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
 * Internal server-to-server endpoint for dropping every OSS-side row of a
 * fully-purged tenant. Called by the ops-console's 30-day purge cron after
 * each member has been erased and the tenant connector dropped.
 *
 * <p><strong>It refuses every call while the erasure path across the service
 * boundary is missing</strong> — see "What this endpoint does today" below.
 *
 * <h3>Contract</h3>
 *
 * <p>Same security shape as {@link EraseSubjectResource}: shared-secret
 * bearer token via {@code kumbuka.internal.erasure.token}, misroute
 * guard against the resolver's current tenant. Returns per-table
 * delete counts only — never content.
 *
 * <h3>What this endpoint does today: it refuses</h3>
 *
 * <p>Every call that gets past the checks below is refused with
 * {@link ErasurePathIncomplete}, before anything changes. The reason is that
 * the purge this endpoint is asked for cannot be carried out: the memory
 * tables are not this service's to drop any more, the path that would ask the
 * memory service for its share does not exist yet, and dropping the core's
 * own rows alone would strip a tenant of the scopes its entries still hang
 * from while the provider reads the answer as a completed purge.
 *
 * <p>{@link TenantDataPurgeService} is that share, and it keeps its tests. In
 * this state it has no caller: the conductor of the erasure path is what will
 * call it, and the same commission is what removes the refusal.
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

        // Last, and after every check that was here before it: the erasure
        // path across the service boundary does not exist, so this call is
        // refused before the core's own share runs. Placed here so a caller
        // who does not hold the shared secret still learns nothing new — the
        // 401 and the two 400s answer first, exactly as they did.
        return ErasurePathIncomplete.refuse(
            "/api/internal/purge-tenant", resolvedTenant, ErasurePathIncomplete.PURGE_MESSAGE);
    }
}
