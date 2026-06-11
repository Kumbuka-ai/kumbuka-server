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
 * Connector card backend. The connector client (kumbuka-connector) is
 * confidential + PKCE (ADR-0006). This resource surfaces the canonical
 * endpoint URL + client_id + masked secret, and lets admins rotate the
 * secret in place. Keycloak is the source of truth for the secret —
 * neither the backend nor any cookie stores it.
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
        String mcpUrl = resolveMcpUrl();
        return new ConnectorView(
            mcpUrl,
            config.connectorClientId(),
            keycloak.getConnectorSecretMasked(config.connectorClientId()),
            "Keycloak",
            mcpUrl
        );
    }

    /**
     * The tenant-correct public MCP URL the console displays (D-CORE-4). CE:
     * {@code publicBaseUrl + /mcp}. SaaS: the configured template with the
     * {@code <alias>} placeholder replaced by the request-bound tenant's
     * {@code team.alias} (Hibernate {@code @TenantId} narrows the query to the
     * current tenant). {@code endpoint} mirrors this value so no surface ever
     * shows the central host for a tenant.
     */
    private String resolveMcpUrl() {
        Team team = Team.findAll().firstResult();
        String alias = team != null ? team.alias : null;
        return resolveMcpUrl(config.mcpPublicUrlTemplate().orElse(""), config.publicBaseUrl(), alias);
    }

    /** Pure substitution — package-private + static so it unit-tests without CDI/DB. */
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
        String actor = identity.getPrincipal().getName();
        String masked = keycloak.rotateConnectorSecret(config.connectorClientId(), actor);
        return new RotateResult(masked);
    }
}
