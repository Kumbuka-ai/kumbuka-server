package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.config.MemoryConfig;
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
 * <p><strong>SaaS</strong>: the connector is the same single generic
 * {@code kumbuka-connector} client (confidential + PKCE), shared across every
 * tenant — a later revision retired the per-tenant {@code kumbuka-connector-<alias>}
 * client. Its secret is <em>provider-managed</em>: rendered into the realm
 * config by the platform and never held by a team, so the card neither exposes
 * nor rotates it. SaaS is detected by the presence of the MCP URL template
 * (same signal {@link #resolveMcpUrl} uses).
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
        String mcpUrl = resolveMcpUrl(template, config.publicBaseUrl());
        String clientId = resolveClientId(config.connectorClientId());
        // SaaS: the connector secret is provider-managed (rendered into the
        // realm config), so it is never exposed from the team console. CE keeps
        // a rotatable, admin-visible masked secret.
        String secretMasked = isSaas(template)
            ? null
            : keycloak.getConnectorSecretMasked(config.connectorClientId());
        return new ConnectorView(mcpUrl, clientId, secretMasked, "Keycloak", mcpUrl);
    }

    /** True when the deployment is SaaS (the MCP URL template is set). */
    static boolean isSaas(String template) {
        return template != null && !template.isBlank();
    }

    /**
     * The connector's Keycloak client_id: always the configured base client
     * ({@code kumbuka-connector}). A later revision retired the per-tenant
     * {@code kumbuka-connector-<alias>} client, so CE and SaaS now address the
     * one generic client. Package-private + static so it unit-tests without
     * CDI/DB.
     */
    static String resolveClientId(String baseClientId) {
        return baseClientId;
    }

    /**
     * The public MCP URL the console displays. CE (empty template):
     * {@code publicBaseUrl + /mcp}. SaaS (template set): the configured template
     * verbatim — that is now the single generic
     * {@code https://mcp.kumbuka.ai/mcp} endpoint, with no per-tenant
     * {@code <alias>} placeholder (tenant resolution is token-derived). Pure —
     * package-private + static so it unit-tests without CDI/DB.
     */
    static String resolveMcpUrl(String template, String publicBaseUrl) {
        if (template == null || template.isBlank()) {
            return publicBaseUrl + "/mcp";
        }
        return template;
    }

    // Note: an earlier draft suggested `/secret:rotate`. We use `/secret/rotate`
    // instead because RestAssured (and some HTTP libraries) interpret a
    // colon in the URI as a port separator. Behaviour is identical;
    // documentation should reflect the slash form.
    @POST
    @Path("/secret/rotate")
    @RolesAllowed("admin")
    public RotateResult rotate() {
        // SaaS: the connector secret is provider-managed, so there is nothing
        // for a team to rotate from the console.
        if (isSaas(config.mcpPublicUrlTemplate().orElse(""))) {
            throw new jakarta.ws.rs.WebApplicationException(
                "connector secret rotation is not available on the hosted platform — "
                + "the connector secret is provider-managed",
                jakarta.ws.rs.core.Response.Status.CONFLICT);
        }
        String actor = identity.getPrincipal().getName();
        String masked = keycloak.rotateConnectorSecret(config.connectorClientId(), actor);
        return new RotateResult(masked);
    }
}
