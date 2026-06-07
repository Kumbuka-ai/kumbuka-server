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
        String idpName
    ) {}

    public record RotateResult(String clientSecretMasked) {}

    @GET
    @RolesAllowed({"admin", "member"})
    public ConnectorView get() {
        return new ConnectorView(
            config.publicBaseUrl() + "/mcp",
            config.connectorClientId(),
            keycloak.getConnectorSecretMasked(config.connectorClientId()),
            "Keycloak"
        );
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
