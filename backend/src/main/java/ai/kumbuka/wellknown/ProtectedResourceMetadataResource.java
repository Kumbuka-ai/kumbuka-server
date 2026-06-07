package ai.kumbuka.wellknown;

import ai.kumbuka.config.MemoryConfig;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata per RFC 9728.
 *
 * claude.ai discovers the authorization server (our Keycloak realm) by
 * fetching this endpoint when it encounters a 401 on /mcp. The MCP spec
 * requires the resource server to advertise the authorisation servers
 * that issue tokens it accepts, plus how those tokens are presented.
 *
 * Public — no auth, no cookies. Cache-friendly.
 */
@Path("/.well-known/oauth-protected-resource")
@Produces(MediaType.APPLICATION_JSON)
public class ProtectedResourceMetadataResource {

    @Inject MemoryConfig config;

    @GET
    @PermitAll
    public Map<String, Object> metadata() {
        String base = config.publicBaseUrl();
        String issuer = config.authBaseUrl() + "/realms/" + config.realm();
        String resource = base + "/mcp";

        return Map.ofEntries(
            Map.entry("resource", resource),
            Map.entry("authorization_servers", List.of(issuer)),
            Map.entry("bearer_methods_supported", List.of("header")),
            Map.entry("resource_signing_alg_values_supported", List.of("RS256")),
            Map.entry("scopes_supported", List.of("openid", "profile", "email")),
            // Helpful breadcrumb for the connector card / inspector.
            Map.entry("resource_documentation", base + "/docs/connector")
        );
    }
}
