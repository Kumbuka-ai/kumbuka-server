package ai.kumbuka.wellknown;

import ai.kumbuka.config.MemoryConfig;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata per RFC 9728.
 *
 * <p>claude.ai discovers the authorization server (our Keycloak realm) by
 * fetching this endpoint when it encounters a 401 on {@code /mcp}. The MCP
 * spec requires the resource server to advertise the authorisation servers
 * that issue tokens it accepts, plus how those tokens are presented.
 *
 * <h2>Per-tenant resource URL (D-OPS-25)</h2>
 * Under the SaaS profile the MCP surface is reached at
 * {@code https://<alias>.kumbuka.ai/mcp}, and the connector's
 * {@code resource} parameter (RFC 8707) must match the host the client
 * actually reached us on — otherwise claude.ai rejects the discovered
 * resource as inconsistent with its connector configuration. We derive the
 * URL from {@link UriInfo}, which Quarkus populates from
 * {@code X-Forwarded-*} (proxy forwarding is enabled in
 * application.properties), so the same code path produces the correct
 * tenant-specific URL behind Caddy without any per-tenant configuration.
 *
 * <p>For the CE/single-tenant deployment this is also correct: the host
 * reflected back is whatever Caddy fronts the install on.
 *
 * <p>The authorisation server is always the central realm
 * ({@code auth.kumbuka.ai}); tenants share one Keycloak realm with
 * Organizations as the tenant axis (D-OPS-24).
 *
 * <p>Public — no auth, no cookies. Cache-friendly.
 */
@Path("/.well-known/oauth-protected-resource")
@Produces(MediaType.APPLICATION_JSON)
public class ProtectedResourceMetadataResource {

    @Inject MemoryConfig config;

    @GET
    @PermitAll
    public Map<String, Object> metadata(@Context UriInfo uriInfo) {
        // Base URI honours X-Forwarded-Host/Proto behind Caddy, so this
        // returns https://<tenant>.kumbuka.ai/ under SaaS and the CE host
        // for a single-tenant install — the resource server NEVER needs to
        // know which tenant it is, it just reflects the host the client
        // reached it on.
        URI base = uriInfo.getBaseUri();
        String resource = uriInfo.getBaseUriBuilder().path("mcp").build().toString();
        // Docs / connector page stays on the canonical brand host —
        // marketing/help content is global, not per-tenant.
        String docsBase = config.publicBaseUrl();

        String issuer = config.authBaseUrl() + "/realms/" + config.realm();

        return Map.ofEntries(
            Map.entry("resource", resource),
            Map.entry("authorization_servers", List.of(issuer)),
            Map.entry("bearer_methods_supported", List.of("header")),
            Map.entry("resource_signing_alg_values_supported", List.of("RS256")),
            Map.entry("scopes_supported", List.of("openid", "profile", "email")),
            // Helpful breadcrumb for the connector card / inspector.
            Map.entry("resource_documentation", docsBase + "/docs/connector"),
            // Echoed for diagnostics (matches the host the client reached).
            Map.entry("resource_base_uri", base.toString())
        );
    }
}
