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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
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
 * <p>D-OPS-25: under SaaS the {@code resource} URL must reflect the host
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
                .body("resource_signing_alg_values_supported", hasItem(equalTo("RS256")));
    }

    @Test
    void resource_reflectsTheBaseUriCarriedByUriInfo() {
        Map<String, Object> doc = invokeWithBaseUri("https://acme.kumbuka.ai/");
        assertThat((String) doc.get("resource")).isEqualTo("https://acme.kumbuka.ai/mcp");
        assertThat((String) doc.get("resource_base_uri")).isEqualTo("https://acme.kumbuka.ai/");
        // Authorisation server stays central — tenancy axis is the
        // Keycloak Organization, not the realm (D-OPS-24).
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
            @Override public UUID   tenantId()       { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
            @Override public String realm()          { return "kumbuka"; }
            @Override public String connectorClientId() { return "kumbuka-connector"; }
            @Override public int    loadContextPerTypeLimit() { return 20; }
        };
    }
}
