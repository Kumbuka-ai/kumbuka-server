package ai.kumbuka.admin;

import ai.kumbuka.erasure.MemberErasureService;
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
import static org.hamcrest.Matchers.nullValue;
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
    @InjectMock MemberErasureService erasure;

    private static KeycloakUser user(String id, String email, String role, String status) {
        return new KeycloakUser(id, email, email, "First", "Last", role, status, Instant.EPOCH);
    }

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

    // ---------- P0 read-authz: roster is admin-only ---------------------------

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void list_asMember_isForbidden() {
        // The full roster (email/role/status) must never reach a plain member.
        given()
            .when().get("/api/users")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void directory_asMember_returnsNamesOnly_noPii() {
        when(keycloak.listUsers()).thenReturn(List.of(
            new KeycloakUser("k1", "alice", "alice@kumbuka.ai", "Alice", "Smith", "admin", "active", Instant.EPOCH),
            new KeycloakUser("k2", "bob", "bob@kumbuka.ai", "Bob", "Jones", "member", "invited", Instant.EPOCH)
        ));

        given()
            .when().get("/api/users/directory")
            .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].subject", equalTo("k1"))
                .body("[0].displayName", equalTo("Alice Smith"))
                // PII is NOT projected for members
                .body("[0].email", nullValue())
                .body("[0].role", nullValue())
                .body("[0].status", nullValue());
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void directory_asAdmin_isAllowed() {
        when(keycloak.listUsers()).thenReturn(List.of(
            new KeycloakUser("k1", "alice", "alice@kumbuka.ai", "Alice", "Smith", "admin", "active", Instant.EPOCH)
        ));

        given()
            .when().get("/api/users/directory")
            .then().statusCode(200).body("$", hasSize(1))
                .body("[0].displayName", equalTo("Alice Smith"));
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

    // ---------- erasure (D-OPS-16 rev., team-admin primary path) --------------

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void erase_happyPath_purgesDeletesAndReturnsCounts() {
        when(keycloak.findById("erase-1"))
            .thenReturn(user("erase-1", "victim@kumbuka.ai", "member", "active"));
        when(erasure.eraseSubject("erase-1"))
            .thenReturn(new MemberErasureService.EraseResult(2, 1, 0));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"typedConfirm": "victim@kumbuka.ai"}
                """)
            .when().post("/api/users/erase-1/erase")
            .then()
                .statusCode(200)
                .body("privatePurged", equalTo(2))
                .body("sharedTombstoned", equalTo(1))
                .body("keycloakRemoved", equalTo(true));

        verify(erasure).eraseSubject("erase-1");
        verify(keycloak).deleteUser("erase-1");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void erase_keycloakDeleteFails_reportsKcRemovedFalse_doesNotUndoPurge() {
        when(keycloak.findById("erase-kc"))
            .thenReturn(user("erase-kc", "victim@kumbuka.ai", "member", "active"));
        when(erasure.eraseSubject("erase-kc"))
            .thenReturn(new MemberErasureService.EraseResult(3, 0, 0));
        org.mockito.Mockito.doThrow(new RuntimeException("kc down"))
            .when(keycloak).deleteUser("erase-kc");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"typedConfirm": "victim@kumbuka.ai"}
                """)
            .when().post("/api/users/erase-kc/erase")
            .then()
                .statusCode(200)
                // Content is already purged (lawful basis) — we report the KC
                // failure rather than roll back the erase.
                .body("privatePurged", equalTo(3))
                .body("keycloakRemoved", equalTo(false));

        verify(erasure).eraseSubject("erase-kc");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void erase_typedConfirmMismatch_rejectsAndDoesNotPurge() {
        when(keycloak.findById("erase-2"))
            .thenReturn(user("erase-2", "victim@kumbuka.ai", "member", "active"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"typedConfirm": "wrong@kumbuka.ai"}
                """)
            .when().post("/api/users/erase-2/erase")
            .then().statusCode(400);

        verify(erasure, org.mockito.Mockito.never()).eraseSubject(any());
        verify(keycloak, org.mockito.Mockito.never()).deleteUser(any());
    }

    @Test
    @TestSecurity(user = "self-admin", roles = {"admin"})
    void erase_self_isRejected() {
        when(keycloak.findById("self-admin"))
            .thenReturn(user("self-admin", "me@kumbuka.ai", "admin", "active"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"typedConfirm": "me@kumbuka.ai"}
                """)
            .when().post("/api/users/self-admin/erase")
            .then().statusCode(400);

        verify(erasure, org.mockito.Mockito.never()).eraseSubject(any());
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void erase_lastAdmin_isRejected() {
        when(keycloak.findById("only-admin"))
            .thenReturn(user("only-admin", "boss@kumbuka.ai", "admin", "active"));
        when(keycloak.listUsers())
            .thenReturn(List.of(user("only-admin", "boss@kumbuka.ai", "admin", "active")));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"typedConfirm": "boss@kumbuka.ai"}
                """)
            .when().post("/api/users/only-admin/erase")
            .then().statusCode(400);

        verify(erasure, org.mockito.Mockito.never()).eraseSubject(any());
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void erase_asMember_isForbidden() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"typedConfirm": "x@x"}
                """)
            .when().post("/api/users/whoever/erase")
            .then().statusCode(403);

        verify(erasure, org.mockito.Mockito.never()).eraseSubject(any());
    }

    // ---------- invite lifecycle (re-invite / cancel) ------------------------

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void resendInvite_invitedMember_delegates() {
        when(keycloak.findById("inv-1"))
            .thenReturn(user("inv-1", "pending@kumbuka.ai", "member", "invited"));

        given().when().post("/api/users/inv-1/resend-invite")
            .then().statusCode(204);

        verify(keycloak).resendInvite("inv-1");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void resendInvite_activeMember_rejectedAs400() {
        when(keycloak.findById("act-1"))
            .thenReturn(user("act-1", "active@kumbuka.ai", "member", "active"));

        given().when().post("/api/users/act-1/resend-invite")
            .then().statusCode(400);

        verify(keycloak, org.mockito.Mockito.never()).resendInvite(any());
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void cancelInvite_invitedMember_deletesKcUser() {
        when(keycloak.findById("inv-2"))
            .thenReturn(user("inv-2", "pending2@kumbuka.ai", "member", "invited"));

        given().when().delete("/api/users/inv-2")
            .then().statusCode(204);

        verify(keycloak).deleteUser("inv-2");
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void cancelInvite_activeMember_rejectedAs409() {
        when(keycloak.findById("act-2"))
            .thenReturn(user("act-2", "active2@kumbuka.ai", "member", "active"));

        given().when().delete("/api/users/act-2")
            .then().statusCode(409);

        verify(keycloak, org.mockito.Mockito.never()).deleteUser(any());
    }
}
