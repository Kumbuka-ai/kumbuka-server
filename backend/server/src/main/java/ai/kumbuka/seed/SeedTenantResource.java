package ai.kumbuka.seed;

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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal server-to-server endpoint that plants the protected D-CORE-11
 * seed entries into the current tenant. Called by the ops-console provider
 * during {@code TenantProvisioningService.createTenant}, and may be invoked
 * manually for the one-time upgrade of pre-D-CORE-11 tenants (the live
 * johannesbayer tenant's three how-to entries flip to protected on the
 * first call).
 *
 * <h3>Security model</h3>
 *
 * Mirrors {@code /api/internal/erase-subject} (ADR-0015) — {@code @PermitAll}
 * + shared-secret bearer token via {@link SeedConfig#token()}. Fail-loud
 * (503) when unset.
 *
 * <h3>Tenant scoping</h3>
 *
 * The body declares the tenant being seeded; the OSS-side {@link TenantResolver}
 * binds {@code app.tenant_id} for the transaction. A request whose body
 * {@code tenantId} doesn't match the resolved tenant is refused (400).
 */
@Path("/api/internal/seed-tenant")
@PermitAll
@TenantBound
public class SeedTenantResource {

    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    @Inject SeedConfig config;
    @Inject TenantResolver resolver;
    @Inject TenantSeedService seedService;

    public record SeedRequest(UUID tenantId) {}

    public record SeedResponse(
        UUID tenantId,
        int seedsApplied,        // count of fixture entries upserted
        java.util.List<String> keys
    ) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response seed(@HeaderParam("Authorization") String authHeader,
                         SeedRequest req) {
        final String configured = config.token().orElse("").trim();
        if (configured.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                    KEY_ERROR, "seed_endpoint_not_configured",
                    KEY_MESSAGE, "kumbuka.internal.seed.token is unset"))
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
            // Refuse rather than silently scope to our own tenant — the
            // provider must address the correct backend explicitly.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(KEY_ERROR, "tenant_mismatch");
            body.put(KEY_MESSAGE, "request tenant does not match this server's tenant");
            body.put("requested", req.tenantId().toString());
            body.put("resolved", resolvedTenant.toString());
            return Response.status(Response.Status.BAD_REQUEST).entity(body).build();
        }

        seedService.seedCurrentTenant();
        return Response.ok(new SeedResponse(
            resolvedTenant,
            seedService.fixtureSize(),
            seedService.fixtureKeys())).build();
    }
}
