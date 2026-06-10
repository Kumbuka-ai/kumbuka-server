package ai.kumbuka.wellknown;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Verifies the RFC 9728 metadata document so MCP clients (claude.ai, MCP
 * Inspector) can discover the authorization server from the resource
 * server.
 *
 * <p>D-OPS-25: under SaaS the {@code resource} URL must reflect the host
 * the client reached (e.g. {@code https://acme.kumbuka.ai/mcp}) — claude.ai
 * rejects a connector whose advertised resource does not match its
 * configured endpoint. Quarkus's proxy forwarding honours
 * {@code X-Forwarded-Host}, so we simulate that here.
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
    void resource_reflectsForwardedHost() {
        // Simulate Caddy in front of us forwarding a tenant subdomain.
        // Quarkus's quarkus.http.proxy.enable-forwarded-host=true (set in
        // application.properties) must turn this into the base URI the
        // JAX-RS UriInfo reports.
        given()
            .header("X-Forwarded-Host", "acme.kumbuka.ai")
            .header("X-Forwarded-Proto", "https")
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                .body("resource", containsString("acme.kumbuka.ai/mcp"))
                .body("resource", endsWith("/mcp"))
                // The auth server remains central — tenancy axis is the
                // Keycloak Organization, not the realm (D-OPS-24).
                .body("authorization_servers", hasItem(endsWith("/realms/kumbuka")));
    }

    @Test
    void resource_reflectsDifferentTenantHostIndependently() {
        // Two different tenant hosts must each get their own resource URL —
        // no caching of "the" resource across requests.
        given()
            .header("X-Forwarded-Host", "beta.kumbuka.ai")
            .header("X-Forwarded-Proto", "https")
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                .body("resource", containsString("beta.kumbuka.ai/mcp"));

        given()
            .header("X-Forwarded-Host", "gamma.kumbuka.ai")
            .header("X-Forwarded-Proto", "https")
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                .body("resource", containsString("gamma.kumbuka.ai/mcp"));
    }
}
