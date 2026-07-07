package ai.kumbuka.admin;

import ai.kumbuka.keycloak.KeycloakAdminService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BUG-18 regression lock. On the hosted (SaaS) platform the connector card must
 * resolve the single generic client id ({@code kumbuka-connector}, NOT a
 * retired {@code kumbuka-connector-<alias>}), must not expose a secret, and must
 * refuse rotation with 409.
 *
 * <p>The SaaS branch is reached by setting the MCP URL template — the same
 * signal {@link AdminConnectorResource#isSaas(String)} keys on — via a
 * {@link TestProfile}. This is the path the pre-ADR-0032 tests never covered:
 * they only exercised the CE case (blank template). It cannot share
 * {@link AdminConnectorResourceTest} because that class asserts the CE
 * behaviour (a visible masked secret), which the template would break.
 */
@QuarkusTest
@TestProfile(AdminConnectorResourceSaasTest.SaasProfile.class)
class AdminConnectorResourceSaasTest {

    public static class SaasProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.mcp.public-url-template", "https://mcp.kumbuka.ai/mcp");
        }
    }

    @InjectMock KeycloakAdminService keycloak;

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void saasCard_usesGenericClientId_andHidesSecret() {
        given()
            .when().get("/api/connector")
            .then()
                .statusCode(200)
                .body("clientId", equalTo("kumbuka-connector"))
                .body("clientSecretMasked", nullValue())
                .body("endpoint", equalTo("https://mcp.kumbuka.ai/mcp"))
                .body("mcpUrl", equalTo("https://mcp.kumbuka.ai/mcp"));

        // The provider-managed secret is never fetched on the SaaS path.
        verify(keycloak, never()).getConnectorSecretMasked(anyString());
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void saasCard_refusesRotation_withConflict() {
        given()
            .when().post("/api/connector/secret/rotate")
            .then().statusCode(409);

        // Rotation is refused before any Keycloak call.
        verify(keycloak, never()).rotateConnectorSecret(anyString(), anyString());
    }
}
