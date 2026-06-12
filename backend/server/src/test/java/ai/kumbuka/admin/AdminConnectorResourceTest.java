package ai.kumbuka.admin;

import ai.kumbuka.keycloak.KeycloakAdminService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class AdminConnectorResourceTest {

    @InjectMock KeycloakAdminService keycloak;

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void get_returnsEndpointAndMaskedSecret() {
        when(keycloak.getConnectorSecretMasked(anyString()))
            .thenReturn("•••••••••••••••••••••••••••••cret");

        given()
            .when().get("/api/connector")
            .then()
                .statusCode(200)
                .body("endpoint", endsWith("/mcp"))
                .body("mcpUrl", endsWith("/mcp"))
                .body("clientId", equalTo("kumbuka-connector"))
                .body("clientSecretMasked", endsWith("cret"))
                .body("idpName", equalTo("Keycloak"));
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void rotate_callsKeycloakAndReturnsNewMasked() {
        when(keycloak.rotateConnectorSecret(anyString(), anyString()))
            .thenReturn("•••••••••••••••••••••••••••••w123");

        given()
            .when().post("/api/connector/secret/rotate")
            .then()
                .statusCode(200)
                .body("clientSecretMasked", endsWith("w123"));

        verify(keycloak).rotateConnectorSecret("kumbuka-connector", "admin-sub");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void rotate_asMember_isForbidden() {
        given()
            .when().post("/api/connector/secret/rotate")
            .then().statusCode(403);
    }

    @Test
    void isSaas_trueOnlyWhenTemplateSet() {
        assertThat(AdminConnectorResource.isSaas("https://<alias>.kumbuka.ai/mcp")).isTrue();
        assertThat(AdminConnectorResource.isSaas("")).isFalse();
        assertThat(AdminConnectorResource.isSaas("   ")).isFalse();
        assertThat(AdminConnectorResource.isSaas(null)).isFalse();
    }

    @Test
    void resolveClientId_saas_appendsTenantAlias() {
        assertThat(AdminConnectorResource.resolveClientId(
                "kumbuka-connector", "https://<alias>.kumbuka.ai/mcp", "acme"))
            .isEqualTo("kumbuka-connector-acme");
    }

    @Test
    void resolveClientId_ce_usesBaseClientId() {
        assertThat(AdminConnectorResource.resolveClientId("kumbuka-connector", "", "acme"))
            .isEqualTo("kumbuka-connector");
        assertThat(AdminConnectorResource.resolveClientId("kumbuka-connector", null, null))
            .isEqualTo("kumbuka-connector");
    }

    @Test
    void resolveClientId_saasWithoutAlias_fallsBackToBase() {
        assertThat(AdminConnectorResource.resolveClientId(
                "kumbuka-connector", "https://<alias>.kumbuka.ai/mcp", null))
            .isEqualTo("kumbuka-connector");
    }

    @Test
    void mask_helper_leavesShortSecretsFullyMasked() {
        assertThat(KeycloakAdminService.mask("abcd"))
            .isEqualTo("••••");
        assertThat(KeycloakAdminService.mask("sk_live_abcdef12"))
            .isEqualTo("••••••••••••ef12");
        assertThat(KeycloakAdminService.mask(null)).isNull();
    }
}
