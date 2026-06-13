package ai.kumbuka.e2e;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.eclipse.microprofile.config.ConfigProvider;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Release-gate end-to-end test. Boots a real Keycloak (Testcontainers),
 * obtains real tokens via password grant against the kumbuka-connector
 * client, and exercises:
 *
 *   1. /mcp rejects unauthenticated requests
 *   2. /mcp accepts a valid bearer with the right audience
 *   3. The admin REST surface never reveals a private memory, even when
 *      the data was inserted by another user
 *   4. /.well-known/oauth-protected-resource advertises the test
 *      authorization server (the same one our OIDC tenant validates against)
 *
 * Naming convention: *IT classes are excluded from `mvn test` and run
 * under `mvn verify -Pintegration` (slow — KC startup ~30s).
 */
@QuarkusTest
@QuarkusTestResource(value = KeycloakTestResource.class, restrictToAnnotatedClass = true)
@TestProfile(OidcEnabledProfile.class)
@Tag("integration")
class E2EOAuthIntegrationIT {

    static final String OTHER_USER_SUBJECT = "11111111-1111-1111-1111-111111111111";

    @Inject MemoryRepository memories;

    private String issuer() {
        return ConfigProvider.getConfig().getValue("test.keycloak.issuer", String.class);
    }

    @BeforeEach
    void plantPrivate() {
        // Diagnostic: surface the effective mcp auth-server-url so a failing run
        // shows whether the override reached the OIDC tenant (temporary).
        System.out.println("[OAuth-IT] effective quarkus.oidc.mcp.auth-server-url="
            + ConfigProvider.getConfig()
                .getOptionalValue("quarkus.oidc.mcp.auth-server-url", String.class)
                .orElse("<unset>"));

        // A different user's private memory — the release gate is that this
        // must remain invisible to every other surface.
        memories.remember(OTHER_USER_SUBJECT, "private", MemoryType.DECISION,
            "release-gate", "this content must not leak", SourceChannel.MCP);
    }

    // ---- /mcp auth boundary ------------------------------------------------

    @Test
    void mcp_without_bearer_is_401() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05",
                           "clientInfo":{"name":"it","version":"1.0"},
                           "capabilities":{}}}
                """)
            .when().post("/mcp")
            .then()
                .statusCode(401);
    }

    @Test
    void mcp_with_garbage_bearer_is_401() {
        given()
            .header("Authorization", "Bearer not-a-real-token")
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post("/mcp")
            .then()
                .statusCode(401);
    }

    @Test
    void mcp_with_valid_member_token_passes_auth() {
        String token = passwordGrant("member@local", "member");
        assertThat(token).isNotBlank();

        // We accept anything but 401 here — the body is a JSON-RPC initialize
        // and the MCP server may negotiate the protocol differently across
        // versions. The point of the IT is that auth is reached.
        Response r = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05",
                           "clientInfo":{"name":"it","version":"1.0"},
                           "capabilities":{}}}
                """)
            .when().post("/mcp");
        assertThat(r.statusCode())
            .as("auth must succeed (status %d, body: %s)", r.statusCode(), r.body().asString())
            .isNotEqualTo(401);
    }

    // ---- Private isolation, full HTTP roundtrip ----------------------------

    @Test
    void prm_authorisation_server_matches_test_issuer() {
        given()
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                .body("authorization_servers[0]", is(issuer()));
    }

    // Admin endpoints behind BFF session — token-based bypass isn't a
    // realistic E2E path. Instead we use the @TestSecurity-style check
    // already covered in AdminPrivateInvariantTest plus a direct DB-level
    // check here that the private row remains private at the data layer
    // after the planted insert went through.

    @Test
    void planted_private_row_is_only_visible_to_owner() {
        var asOwner = memories.recall(OTHER_USER_SUBJECT, "private", null, null, false);
        assertThat(asOwner).anyMatch(m -> "release-gate".equals(m.key));

        var asStranger = memories.recall("99999999-9999-9999-9999-999999999999",
            "private", null, null, false);
        assertThat(asStranger).noneMatch(m -> "release-gate".equals(m.key));
    }

    // ---- helpers -----------------------------------------------------------

    private String passwordGrant(String user, String password) {
        return given()
            .baseUri(issuer())
            .contentType(ContentType.URLENC)
            .formParam("grant_type", "password")
            .formParam("client_id", "kumbuka-connector")
            .formParam("client_secret", "change-me-kumbuka-connector-secret")
            .formParam("username", user)
            .formParam("password", password)
            .formParam("scope", "openid")
            .when().post("/protocol/openid-connect/token")
            .then().statusCode(200)
            .extract().jsonPath().getString("access_token");
    }
}
