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
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class AdminConnectorResourceTest {

    @InjectMock KeycloakAdminService keycloak;

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void get_returnsClientAndMaskedSecret_andNamesNoEndpointOnCE() {
        when(keycloak.getConnectorSecretMasked(anyString()))
            .thenReturn("•••••••••••••••••••••••••••••cret");

        given()
            .when().get("/api/connector")
            .then()
                .statusCode(200)
                // The client and its secret are the core's to administer and stay.
                .body("clientId", equalTo("kumbuka-connector"))
                .body("clientSecretMasked", endsWith("cret"))
                .body("idpName", equalTo("Keycloak"))
                // The endpoint is not. With no template configured (the CE
                // default of this test profile) the card names none, rather than
                // a path this service stopped serving.
                .body("endpoint", nullValue())
                .body("mcpUrl", nullValue());
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
        // The generic template (no <alias> placeholder) still
        // marks a SaaS deployment.
        assertThat(AdminConnectorResource.isSaas("https://mcp.kumbuka.ai/mcp")).isTrue();
        assertThat(AdminConnectorResource.isSaas("")).isFalse();
        assertThat(AdminConnectorResource.isSaas("   ")).isFalse();
        assertThat(AdminConnectorResource.isSaas(null)).isFalse();
    }

    @Test
    void resolveClientId_alwaysUsesBaseClientId() {
        // one generic connector client for CE and SaaS alike —
        // there is no per-tenant <alias> suffix any more.
        assertThat(AdminConnectorResource.resolveClientId("kumbuka-connector"))
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
