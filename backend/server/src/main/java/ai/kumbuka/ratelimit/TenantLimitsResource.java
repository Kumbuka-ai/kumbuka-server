package ai.kumbuka.ratelimit;

import ai.kumbuka.domain.TenantLimits;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.tenancy.TenantResolver;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Internal server-to-server endpoint that reads and writes a tenant's
 * write-rate limit configuration. Called by the provider's operations
 * backend, never by humans, never by the MCP or admin pipelines.
 *
 * <h3>Security model</h3>
 *
 * <p>{@code @PermitAll} + shared-secret bearer token
 * ({@code kumbuka.internal.limits.token}), exactly like the seed and
 * erasure internal endpoints: unset token → 503 (an unconfigured deploy
 * fails loud), wrong token → 401. The tenant travels in the URL and is
 * validated against the resolver-bound tenant (the internal channel binds
 * it from the caller's explicit tenant header in the hosted overlay; the
 * single-tenant edition resolves its singleton), so a misrouted call is
 * refused with 400 instead of silently configuring the wrong tenant.
 *
 * <h3>Configuration only</h3>
 *
 * <p>This endpoint sets and returns limit NUMBERS. It never exposes bucket
 * fill state, throttle counts, or any usage series — that data is
 * ephemeral limiter state and deliberately has no query surface.
 *
 * <h3>PATCH semantics</h3>
 *
 * <p>The PATCH body carries the complete desired override state: a
 * {@code write} band (or null to clear the per-principal override) and a
 * {@code tenantWrite} band (or null to deactivate the tenant-aggregate
 * bucket). Clearing both deletes the row — absence of a row means the
 * deployment defaults apply. Changes take effect on the next write; no
 * restart, no redeploy.
 */
@Path("/api/internal/tenants/{tenantId}/limits")
@PermitAll
@TenantBound
public class TenantLimitsResource {

    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    @Inject LimitsEndpointConfig config;
    @Inject TenantResolver resolver;
    @Inject RateLimitConfig rateLimitConfig;
    @Inject TenantLimitsProvider limitsProvider;

    /** One band on the wire; all three fields set, or the whole object null. */
    public record BandDto(Integer burstCapacity, Integer refillTokens, Integer refillPeriodSeconds) {

        static BandDto of(Integer burst, Integer tokens, Integer period) {
            return burst == null ? null : new BandDto(burst, tokens, period);
        }

        static BandDto of(WriteRateBand band) {
            return new BandDto(band.burstCapacity(), band.refillTokens(), band.refillPeriodSeconds());
        }

        boolean complete() {
            return burstCapacity != null && refillTokens != null && refillPeriodSeconds != null;
        }

        boolean valid() {
            return complete()
                && burstCapacity > 0 && burstCapacity <= 1_000_000
                && refillTokens > 0 && refillTokens <= 1_000_000
                && refillPeriodSeconds > 0 && refillPeriodSeconds <= 86_400;
        }
    }

    /**
     * Wire shape of GET and of the PATCH response. {@code source} is
     * {@code override} when a per-principal override row is in force, else
     * {@code default}; {@code effective} is the band the limiter applies.
     */
    public record LimitsView(
        UUID tenantId,
        String source,
        BandDto effective,
        BandDto override,
        BandDto defaults,
        BandDto tenantAggregate) {}

    /** PATCH body: the complete desired override state. */
    public record LimitsPatch(BandDto write, BandDto tenantWrite) {}

    @GET
    @Transactional
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@HeaderParam("Authorization") String authHeader,
                        @PathParam("tenantId") UUID tenantId) {
        Response refusal = refuse(authHeader, tenantId);
        if (refusal != null) {
            return refusal;
        }
        return Response.ok(view(tenantId, TenantLimits.findById(tenantId))).build();
    }

    @PATCH
    @Transactional
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response patch(@HeaderParam("Authorization") String authHeader,
                          @PathParam("tenantId") UUID tenantId,
                          LimitsPatch patch) {
        Response refusal = refuse(authHeader, tenantId);
        if (refusal != null) {
            return refusal;
        }
        if (patch == null
                || (patch.write() != null && !patch.write().valid())
                || (patch.tenantWrite() != null && !patch.tenantWrite().valid())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    KEY_ERROR, "invalid_band",
                    KEY_MESSAGE, "each band needs burstCapacity, refillTokens (1..1000000) "
                        + "and refillPeriodSeconds (1..86400); null clears a band"))
                .build();
        }

        TenantLimits row = TenantLimits.findById(tenantId);
        if (patch.write() == null && patch.tenantWrite() == null) {
            if (row != null) {
                row.delete();
            }
        } else {
            if (row == null) {
                row = new TenantLimits();
                row.tenantId = tenantId;
            }
            row.writeBurstCapacity = patch.write() == null ? null : patch.write().burstCapacity();
            row.writeRefillTokens = patch.write() == null ? null : patch.write().refillTokens();
            row.writeRefillPeriodSeconds =
                patch.write() == null ? null : patch.write().refillPeriodSeconds();
            row.tenantWriteBurstCapacity =
                patch.tenantWrite() == null ? null : patch.tenantWrite().burstCapacity();
            row.tenantWriteRefillTokens =
                patch.tenantWrite() == null ? null : patch.tenantWrite().refillTokens();
            row.tenantWriteRefillPeriodSeconds =
                patch.tenantWrite() == null ? null : patch.tenantWrite().refillPeriodSeconds();
            row.updatedAt = Instant.now();
            row.persist();
        }

        // Runtime reconfigurability: the next write re-reads the config.
        limitsProvider.invalidate(tenantId);
        return Response.ok(view(tenantId,
            patch.write() == null && patch.tenantWrite() == null ? null : row)).build();
    }

    /** Shared 503 / 401 / 400 refusal ladder (mirrors the seed/erase endpoints). */
    private Response refuse(String authHeader, UUID tenantId) {
        final String configured = config.token().orElse("").trim();
        if (configured.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                    KEY_ERROR, "limits_endpoint_not_configured",
                    KEY_MESSAGE, "kumbuka.internal.limits.token is unset"))
                .build();
        }
        if (authHeader == null || !authHeader.equals("Bearer " + configured)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of(KEY_ERROR, "unauthorized"))
                .build();
        }
        final UUID resolvedTenant = resolver.currentTenant();
        if (!resolvedTenant.equals(tenantId)) {
            // Misrouted call: refuse rather than silently configuring the
            // wrong tenant.
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    KEY_ERROR, "tenant_mismatch",
                    KEY_MESSAGE, "request tenant does not match the bound tenant"))
                .build();
        }
        return null;
    }

    private LimitsView view(UUID tenantId, TenantLimits row) {
        WriteRateBand defaults = rateLimitConfig.defaultBand();
        BandDto override = row == null ? null
            : BandDto.of(row.writeBurstCapacity, row.writeRefillTokens, row.writeRefillPeriodSeconds);
        BandDto aggregate = row == null ? null
            : BandDto.of(row.tenantWriteBurstCapacity, row.tenantWriteRefillTokens,
                row.tenantWriteRefillPeriodSeconds);
        return new LimitsView(
            tenantId,
            override != null ? "override" : "default",
            override != null ? override : BandDto.of(defaults),
            override,
            BandDto.of(defaults),
            aggregate);
    }
}
