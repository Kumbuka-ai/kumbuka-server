package ai.kumbuka.wellknown;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Verifies the RFC 9728 metadata document so MCP clients (claude.ai,
 * MCP Inspector) can discover the authorization server from the
 * resource server.
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
}
