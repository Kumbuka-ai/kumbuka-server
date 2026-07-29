package ai.kumbuka.admin;

import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakCredential;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * the member-facing /api/credentials surface: type filtering,
 * recovery-presence flag, caller-scoping, and ownership + type enforcement on
 * delete. The {@link KeycloakAdminService} wrapper is mocked (mirrors
 * {@link SessionsResourceTest}); real Keycloak interaction is proven by the
 * console virtual-authenticator round-trip.
 *
 * <p>The security-critical cases are delete of a foreign / unknown id and of a
 * non-self-service type (recovery codes, password): each must 404 (not leak
 * existence) and must NOT delegate to the admin client.
 */
@QuarkusTest
class CredentialsResourceTest {

    @InjectMock KeycloakAdminService keycloak;

    private static KeycloakCredential cred(String id, String type, String label) {
        return new KeycloakCredential(id, type, label, 1_700_000_000_000L);
    }

    @Test
    void list_unauthenticated_is401() {
        given().when().get("/api/credentials").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void list_filtersToSelfServiceTypes_andFlagsRecoveryPresence() {
        when(keycloak.listUserCredentials("member-sub")).thenReturn(List.of(
            cred("c-otp", "otp", "Authenticator"),
            cred("c-pk", "webauthn-passwordless", "MacBook"),
            cred("c-rec", "recovery-authn-codes", null),
            cred("c-pw", "password", null)
        ));

        given()
            .when().get("/api/credentials")
            .then()
                .statusCode(200)
                // password + recovery-authn-codes are filtered out of the list
                .body("credentials", hasSize(2))
                .body("credentials[0].id", equalTo("c-otp"))
                .body("credentials[0].type", equalTo("otp"))
                .body("credentials[1].type", equalTo("webauthn-passwordless"))
                // recovery codes are never listed, but their presence IS flagged
                .body("recoveryCodesConfigured", equalTo(true));

        verify(keycloak).listUserCredentials("member-sub");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void list_recoveryFlagFalse_whenNoRecoveryCredential() {
        when(keycloak.listUserCredentials("member-sub")).thenReturn(List.of(
            cred("c-otp", "otp", "Authenticator")
        ));

        given()
            .when().get("/api/credentials")
            .then()
                .statusCode(200)
                .body("credentials", hasSize(1))
                .body("recoveryCodesConfigured", equalTo(false));
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void delete_ownSelfServiceCredential_delegatesAndReturns204() {
        when(keycloak.listUserCredentials("member-sub")).thenReturn(List.of(
            cred("c-pk", "webauthn", "YubiKey")
        ));

        given()
            .when().delete("/api/credentials/c-pk")
            .then().statusCode(204);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keycloak).removeUserCredential(cap.capture(), cap.capture());
        assertThat(cap.getAllValues()).containsExactly("member-sub", "c-pk");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void delete_foreignOrUnknownId_is404_andDoesNotDelete() {
        when(keycloak.listUserCredentials("member-sub")).thenReturn(List.of(
            cred("c-pk", "webauthn", "YubiKey")
        ));

        given()
            .when().delete("/api/credentials/someone-elses-credential")
            .then().statusCode(404);

        verify(keycloak, never()).removeUserCredential("member-sub", "someone-elses-credential");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void delete_nonSelfServiceType_is404_andDoesNotDelete() {
        // Recovery codes and password are the caller's own credentials but are
        // NOT self-service-deletable — the endpoint must 404 (no type bypass).
        when(keycloak.listUserCredentials("member-sub")).thenReturn(List.of(
            cred("c-rec", "recovery-authn-codes", null),
            cred("c-pw", "password", null)
        ));

        given().when().delete("/api/credentials/c-rec").then().statusCode(404);
        given().when().delete("/api/credentials/c-pw").then().statusCode(404);

        verify(keycloak, never()).removeUserCredential("member-sub", "c-rec");
        verify(keycloak, never()).removeUserCredential("member-sub", "c-pw");
    }

    @Test
    void delete_unauthenticated_is401() {
        given().when().delete("/api/credentials/c-pk").then().statusCode(401);
    }
}
