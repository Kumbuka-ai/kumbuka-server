package ai.kumbuka.admin;

import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the /api/users surface: routing, auth gates, request shape,
 * delegation to {@link KeycloakAdminService}. Real Keycloak interaction
 * is exercised in Phase 11 via Testcontainers; here we mock the service.
 */
@QuarkusTest
class AdminUsersResourceTest {

    @InjectMock KeycloakAdminService keycloak;

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void list_returnsKeycloakUsers() {
        when(keycloak.listUsers()).thenReturn(List.of(
            new KeycloakUser("k1", "alice", "alice@kumbuka.ai", "Alice", "Smith", "admin", "active", Instant.now()),
            new KeycloakUser("k2", "bob", "bob@kumbuka.ai", "Bob", "Jones", "member", "invited", Instant.now())
        ));

        given()
            .when().get("/api/users")
            .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].email", equalTo("alice@kumbuka.ai"))
                .body("[1].status", equalTo("invited"));
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void invite_asMember_isForbidden() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"email": "new@kumbuka.ai", "firstName": "New", "lastName": "Hire", "role": "member"}
                """)
            .when().post("/api/users")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void invite_asAdmin_delegatesToKeycloak() {
        when(keycloak.invite(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new KeycloakUser("k3", "new", "new@kumbuka.ai",
                "New", "Hire", "member", "invited", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"email": "new@kumbuka.ai", "firstName": "New", "lastName": "Hire", "role": "member"}
                """)
            .when().post("/api/users")
            .then()
                .statusCode(201)
                .body("email", equalTo("new@kumbuka.ai"))
                .body("status", equalTo("invited"));

        ArgumentCaptor<String> emailCap = ArgumentCaptor.forClass(String.class);
        verify(keycloak).invite(emailCap.capture(), any(), any(), any());
        assertThat(emailCap.getValue()).isEqualTo("new@kumbuka.ai");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void invite_rejectsInvalidRole() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"email": "x@kumbuka.ai", "role": "superuser"}
                """)
            .when().post("/api/users")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void update_enable_delegatesToKeycloak() {
        when(keycloak.findById("k1")).thenReturn(
            new KeycloakUser("k1", "alice", "alice@kumbuka.ai",
                "Alice", "Smith", "admin", "disabled", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"enabled": true}
                """)
            .when().patch("/api/users/k1")
            .then().statusCode(200);

        verify(keycloak).updateEnabled("k1", true);
    }
}
