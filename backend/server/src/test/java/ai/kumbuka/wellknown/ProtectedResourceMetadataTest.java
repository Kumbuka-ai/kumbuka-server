package ai.kumbuka.wellknown;

import ai.kumbuka.config.MemoryConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFC 9728 metadata document — public access and per-host derivation.
 *
 * <p>The public-access shape is verified via {@link QuarkusTest} (a real
 * HTTP round trip). Per-tenant host derivation is unit-tested directly
 * against {@link ProtectedResourceMetadataResource#metadata(UriInfo)}
 * with a mocked {@link UriInfo}: in production Quarkus's
 * proxy-address-forwarding turns Caddy's {@code X-Forwarded-Host} into
 * the JAX-RS base URI, but driving that path through a Quarkus test
 * server adds noise that doesn't change the unit under test (a single
 * String append). The mock-based test pins the contract.
 *
 * <p>Under SaaS the {@code resource} URL must reflect the host
 * the client reached. claude.ai rejects a connector whose advertised
 * resource diverges from its configured endpoint.
 */
@QuarkusTest
class ProtectedResourceMetadataTest {

    @Test
    void metadata_isPubliclyAccessible() {
        given()
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("resource", endsWith("/mcp"))
                .body("authorization_servers", hasItem(endsWith("/realms/kumbuka")))
                .body("bearer_methods_supported", contains("header"))
                // (c) resource_signing_alg_values_supported is config-driven
                // (ConnectorMetadataConfig) and the default resolves to exactly
                // [RS256] — it must match the AS token signing algorithm.
                .body("resource_signing_alg_values_supported", contains("RS256"));
    }

    /**
     * regression: the resource server MUST advertise the scopes a
     * strict MCP client (Claude/ChatGPT) actually requests — most importantly
     * {@code offline_access} (refresh token). When a requested scope is absent
     * from {@code scopes_supported}, such a client aborts BEFORE the
     * authorization request (nothing reaches Keycloak or us), reporting only a
     * generic authorization failure. The list is also kept deliberately narrow
     * to avoid consent-screen bloat. Runs against the wired default config
     * (ConnectorMetadataConfig) — no overrides that would mask the shipped
     * default.
     */
    @Test
    void scopes_supported_advertisesOfflineAccessAndStaysNarrow() {
        given()
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                // (a) the fix proper: offline_access is advertised.
                .body("scopes_supported", hasItem("offline_access"))
                // (b) the curated OIDC scopes remain present...
                .body("scopes_supported", hasItems("openid", "profile", "email"))
                // ...and the list stays narrow — no consent-bloat scopes.
                .body("scopes_supported", not(hasItem("address")))
                .body("scopes_supported", not(hasItem("phone")))
                .body("scopes_supported", not(hasItem("roles")));
    }

    @Test
    void resource_reflectsTheBaseUriCarriedByUriInfo() {
        Map<String, Object> doc = invokeWithBaseUri("https://acme.kumbuka.ai/");
        assertThat((String) doc.get("resource")).isEqualTo("https://acme.kumbuka.ai/mcp");
        assertThat((String) doc.get("resource_base_uri")).isEqualTo("https://acme.kumbuka.ai/");
        // Authorisation server stays central — tenancy axis is the
        // Keycloak Organization, not the realm.
        @SuppressWarnings("unchecked")
        var authServers = (java.util.List<String>) doc.get("authorization_servers");
        assertThat(authServers).hasSize(1);
        assertThat(authServers.get(0)).endsWith("/realms/kumbuka");
    }

    @Test
    void resource_reflectsADifferentTenantHostIndependently() {
        // Two distinct hosts must each get their own resource URL — no
        // captured state between invocations.
        assertThat((String) invokeWithBaseUri("https://beta.kumbuka.ai/").get("resource"))
            .isEqualTo("https://beta.kumbuka.ai/mcp");
        assertThat((String) invokeWithBaseUri("https://gamma.kumbuka.ai/").get("resource"))
            .isEqualTo("https://gamma.kumbuka.ai/mcp");
    }

    @Test
    void resource_documentation_uses_the_central_brand_host() {
        // Marketing/help content is global, not per-tenant — it must NOT
        // adopt the tenant subdomain.
        Map<String, Object> doc = invokeWithBaseUri("https://acme.kumbuka.ai/");
        assertThat((String) doc.get("resource_documentation"))
            .doesNotContain("acme.kumbuka.ai");
    }

    // -----------------------------------------------------------------------
    // Drive ProtectedResourceMetadataResource directly with a mocked
    // UriInfo + a fake MemoryConfig — proves the per-host derivation
    // contract without any HTTP/Quarkus-forwarding plumbing.
    // -----------------------------------------------------------------------

    private static Map<String, Object> invokeWithBaseUri(String baseUri) {
        ProtectedResourceMetadataResource res = new ProtectedResourceMetadataResource();
        res.config = stubConfig();
        res.connectorMetadata = stubConnectorMetadata();

        UriInfo uri = mock(UriInfo.class);
        when(uri.getBaseUri()).thenReturn(URI.create(baseUri));
        when(uri.getBaseUriBuilder()).thenAnswer(inv -> UriBuilder.fromUri(baseUri));

        return res.metadata(uri);
    }

    private static MemoryConfig stubConfig() {
        return new MemoryConfig() {
            @Override public String publicBaseUrl()  { return "https://kumbuka.ai"; }
            @Override public java.util.Optional<String> mcpPublicUrlTemplate() { return java.util.Optional.empty(); }
            @Override public String authBaseUrl()    { return "https://auth.kumbuka.ai"; }
            @Override public java.util.Optional<String> consoleBaseUrl() { return java.util.Optional.empty(); }
            @Override public UUID   tenantId()       { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
            @Override public String realm()          { return "kumbuka"; }
            @Override public String connectorClientId() { return "kumbuka-connector"; }
            @Override public int    loadContextPerTypeLimit() { return 20; }
            @Override public String systemGuidancePath() { return "/etc/kumbuka/system-conventions.json"; }
        };
    }

    /** Mirrors the shipped ConnectorMetadataConfig defaults (incl. the offline_access advertisement). */
    private static ConnectorMetadataConfig stubConnectorMetadata() {
        return new ConnectorMetadataConfig() {
            @Override public java.util.List<String> scopesSupported() {
                return java.util.List.of("openid", "profile", "email", "offline_access");
            }
            @Override public java.util.List<String> resourceSigningAlgValues() {
                return java.util.List.of("RS256");
            }
        };
    }
}
