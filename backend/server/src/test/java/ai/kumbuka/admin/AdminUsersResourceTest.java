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
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void invite_blankEmail_rejectsAs400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"email": "", "role": "member"}
                """)
            .when().post("/api/users")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void invite_nullEmail_rejectsAs400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"role": "member"}
                """)
            .when().post("/api/users")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void invite_nullRole_defaultsToMember() {
        // Role defaults to 'member' when omitted (the @RolesAllowed gate is
        // already satisfied; this is just the body shape).
        when(keycloak.invite(anyString(), any(), any(), eq("member")))
            .thenReturn(new KeycloakUser("k", "u", "u@x", null, null, "member", "invited", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"email": "u@x"}
                """)
            .when().post("/api/users")
            .then().statusCode(201);

        verify(keycloak).invite("u@x", null, null, "member");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void invite_emailIsTrimmed_beforeForwarding() {
        when(keycloak.invite(eq("u@x"), any(), any(), anyString()))
            .thenReturn(new KeycloakUser("k", "u", "u@x", null, null, "member", "invited", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"email": "  u@x  ", "role": "admin"}
                """)
            .when().post("/api/users")
            .then().statusCode(201);

        // Trim happens in the resource before delegating.
        verify(keycloak).invite("u@x", null, null, "admin");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void update_invalidRole_rejectsAs400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"role": "owner"}
                """)
            .when().patch("/api/users/k1")
            .then().statusCode(400);

        verify(keycloak, org.mockito.Mockito.never()).updateRole(any(), any());
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void update_roleOnly_delegatesUpdateRole_andNotUpdateEnabled() {
        when(keycloak.findById("k1")).thenReturn(
            new KeycloakUser("k1", "alice", "alice@x", null, null, "admin", "active", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"role": "admin"}
                """)
            .when().patch("/api/users/k1")
            .then().statusCode(200);

        verify(keycloak).updateRole("k1", "admin");
        verify(keycloak, org.mockito.Mockito.never()).updateEnabled(any(), org.mockito.Mockito.anyBoolean());
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void update_roleAndEnabled_bothDelegated() {
        when(keycloak.findById("k1")).thenReturn(
            new KeycloakUser("k1", "alice", "alice@x", null, null, "member", "active", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"role": "member", "enabled": true}
                """)
            .when().patch("/api/users/k1")
            .then().statusCode(200);

        verify(keycloak).updateRole("k1", "member");
        verify(keycloak).updateEnabled("k1", true);
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void update_emptyBody_returnsUserUnchanged_noDelegation() {
        // Empty PATCH body: nothing to change, but the endpoint still re-reads
        // and returns the current view. Both update methods stay quiet.
        when(keycloak.findById("k1")).thenReturn(
            new KeycloakUser("k1", "alice", "alice@x", null, null, "member", "active", Instant.now()));

        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().patch("/api/users/k1")
            .then().statusCode(200);

        verify(keycloak, org.mockito.Mockito.never()).updateRole(any(), any());
        verify(keycloak, org.mockito.Mockito.never()).updateEnabled(any(), org.mockito.Mockito.anyBoolean());
    }

    // ---------- mute (D-CORE-2) ----------------------------------------------

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void mute_thenUnmute_persistsAndReadsBack() {
        KeycloakUser u = new KeycloakUser("mute-target", "bob", "bob@x", "Bob", "Jones", "member", "active", Instant.now());
        when(keycloak.findById("mute-target")).thenReturn(u);
        when(keycloak.listUsers()).thenReturn(List.of(u));

        // mute → 200, muted=true in the response (lazily upserts user_account)
        given().contentType(ContentType.JSON).body("""
                {"muted": true}
                """)
            .when().patch("/api/users/mute-target")
            .then().statusCode(200).body("muted", equalTo(true));

        // the team list reflects the persisted mute state
        given().when().get("/api/users")
            .then().statusCode(200)
                .body("find { it.id == 'mute-target' }.muted", equalTo(true));

        // unmute → muted=false, list reflects it
        given().contentType(ContentType.JSON).body("""
                {"muted": false}
                """)
            .when().patch("/api/users/mute-target")
            .then().statusCode(200).body("muted", equalTo(false));

        given().when().get("/api/users")
            .then().statusCode(200)
                .body("find { it.id == 'mute-target' }.muted", equalTo(false));
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void mute_asMember_isForbidden() {
        given().contentType(ContentType.JSON).body("""
                {"muted": true}
                """)
            .when().patch("/api/users/whoever")
            .then().statusCode(403);
    }
}
