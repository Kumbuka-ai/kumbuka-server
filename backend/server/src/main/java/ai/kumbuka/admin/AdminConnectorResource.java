package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.domain.Team;
import ai.kumbuka.keycloak.KeycloakAdminService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Connector card backend.
 *
 * <p><strong>CE</strong>: a single confidential connector client
 * ({@code kumbuka-connector}) with a rotatable secret — the resource surfaces
 * the endpoint URL + client_id + masked secret and lets admins rotate it.
 *
 * <p><strong>SaaS</strong>: the connector is the per-tenant
 * {@code kumbuka-connector-<alias>} client, which is PUBLIC + PKCE (ADR-0006
 * Fallback A) and therefore has <em>no</em> secret. The card then shows the
 * per-tenant client_id and no secret, and rotation is not available. SaaS is
 * detected by the presence of the tenant-aware MCP URL template (same signal
 * {@link #resolveMcpUrl} uses).
 */
@TenantBound
@Transactional
@Path("/api/connector")
@Produces(MediaType.APPLICATION_JSON)
public class AdminConnectorResource {

    @Inject MemoryConfig config;
    @Inject KeycloakAdminService keycloak;
    @Inject SecurityIdentity identity;

    public record ConnectorView(
        String endpoint,
        String clientId,
        String clientSecretMasked,
        String idpName,
        String mcpUrl
    ) {}

    public record RotateResult(String clientSecretMasked) {}

    @GET
    @RolesAllowed({"admin", "member"})
    public ConnectorView get() {
        String template = config.mcpPublicUrlTemplate().orElse("");
        String alias = currentAlias();
        String mcpUrl = resolveMcpUrl(template, config.publicBaseUrl(), alias);
        String clientId = resolveClientId(config.connectorClientId(), template, alias);
        // SaaS connectors are public + PKCE — no secret to show or rotate.
        String secretMasked = isSaas(template)
            ? null
            : keycloak.getConnectorSecretMasked(config.connectorClientId());
        return new ConnectorView(mcpUrl, clientId, secretMasked, "Keycloak", mcpUrl);
    }

    /** True when the deployment is SaaS (the tenant-aware MCP URL template is set). */
    static boolean isSaas(String template) {
        return template != null && !template.isBlank();
    }

    /**
     * The connector's Keycloak client_id. SaaS: the per-tenant public client
     * {@code <base>-<alias>} (e.g. {@code kumbuka-connector-acme}). CE: the
     * single {@code <base>} client. Falls back to the base id when SaaS is
     * indicated but no alias is resolvable (defensive — should not happen).
     */
    static String resolveClientId(String baseClientId, String template, String alias) {
        if (isSaas(template) && alias != null && !alias.isBlank()) {
            return baseClientId + "-" + alias;
        }
        return baseClientId;
    }

    /** The request-bound tenant's alias (Hibernate @TenantId narrows the query), or null. */
    private String currentAlias() {
        Team team = Team.findAll().firstResult();
        return team != null ? team.alias : null;
    }

    /**
     * The tenant-correct public MCP URL the console displays (D-CORE-4). CE:
     * {@code publicBaseUrl + /mcp}. SaaS: the configured template with the
     * {@code <alias>} placeholder replaced by the request-bound tenant's
     * {@code team.alias}. Pure substitution — package-private + static so it
     * unit-tests without CDI/DB.
     */
    static String resolveMcpUrl(String template, String publicBaseUrl, String alias) {
        if (template == null || template.isBlank()) {
            return publicBaseUrl + "/mcp";
        }
        if (template.contains("<alias>")) {
            if (alias == null || alias.isBlank()) {
                return publicBaseUrl + "/mcp";
            }
            return template.replace("<alias>", alias);
        }
        return template;
    }

    // Note: the handoff §D suggests `/secret:rotate`. We use `/secret/rotate`
    // instead because RestAssured (and some HTTP libraries) interpret a
    // colon in the URI as a port separator. Behaviour is identical;
    // documentation should reflect the slash form.
    @POST
    @Path("/secret/rotate")
    @RolesAllowed("admin")
    public RotateResult rotate() {
        // SaaS connectors are public + PKCE — there is no secret to rotate.
        if (isSaas(config.mcpPublicUrlTemplate().orElse(""))) {
            throw new jakarta.ws.rs.WebApplicationException(
                "connector secret rotation is not available — the SaaS connector is public + PKCE",
                jakarta.ws.rs.core.Response.Status.CONFLICT);
        }
        String actor = identity.getPrincipal().getName();
        String masked = keycloak.rotateConnectorSecret(config.connectorClientId(), actor);
        return new RotateResult(masked);
    }
}
