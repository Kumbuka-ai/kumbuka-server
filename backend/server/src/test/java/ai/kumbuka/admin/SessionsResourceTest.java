package ai.kumbuka.admin;

import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakSession;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.quarkus.test.InjectMock;

/**
 * D-CORE-8 — the member-facing /api/sessions surface: routing, auth gate,
 * caller-scoping, and ownership enforcement on terminate. Real Keycloak
 * interaction is exercised via Testcontainers elsewhere; here the
 * {@link KeycloakAdminService} wrapper is mocked.
 *
 * <p>The security-critical case is {@code terminate} of a session id that is
 * not the caller's: it must 404 (not leak existence) and must NOT delegate.
 */
@QuarkusTest
class SessionsResourceTest {

    @InjectMock KeycloakAdminService keycloak;
    @InjectMock CurrentSessionId currentSession;

    private static KeycloakSession session(String id, String... clients) {
        return new KeycloakSession(
            id, "203.0.113.7",
            Instant.parse("2026-06-13T10:00:00Z"),
            Instant.parse("2026-06-13T10:30:00Z"),
            false, List.of(clients));
    }

    @Test
    void list_unauthenticated_is401() {
        given().when().get("/api/sessions").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void list_returnsOwnSessions_scopedToCaller() {
        when(keycloak.listUserSessions("member-sub")).thenReturn(List.of(
            session("s1", "kumbuka-admin"),
            session("s2", "kumbuka-connector-acme")
        ));

        given()
            .when().get("/api/sessions")
            .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].id", equalTo("s1"))
                .body("[0].clients[0]", equalTo("kumbuka-admin"))
                .body("[0].current", equalTo(false))
                .body("[1].id", equalTo("s2"));

        // The endpoint passes the caller's own subject — never an arbitrary id.
        verify(keycloak).listUserSessions("member-sub");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void terminate_ownSession_delegatesAndReturns204() {
        when(keycloak.listUserSessions("member-sub")).thenReturn(List.of(session("s1")));

        given()
            .when().delete("/api/sessions/s1")
            .then().statusCode(204);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(keycloak).logoutSession(cap.capture());
        assertThat(cap.getValue()).isEqualTo("s1");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void terminate_foreignOrUnknownSession_is404_andDoesNotLogout() {
        // The caller owns only s1; terminating any other id must not delegate.
        when(keycloak.listUserSessions("member-sub")).thenReturn(List.of(session("s1")));

        given()
            .when().delete("/api/sessions/someone-elses-session")
            .then().statusCode(404);

        verify(keycloak, never()).logoutSession("someone-elses-session");
    }

    @Test
    void terminate_unauthenticated_is401() {
        given().when().delete("/api/sessions/s1").then().statusCode(401);
    }

    // ---- current-session detection + logout-others (F-0082) ----------------

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void list_marksCurrentSession_fromSidClaim() {
        when(currentSession.get()).thenReturn("s1");
        when(keycloak.listUserSessions("member-sub")).thenReturn(List.of(
            session("s1", "kumbuka-admin"),
            session("s2", "kumbuka-connector-acme")
        ));

        given()
            .when().get("/api/sessions")
            .then()
                .statusCode(200)
                .body("[0].id", equalTo("s1"))
                .body("[0].current", equalTo(true))
                .body("[1].id", equalTo("s2"))
                .body("[1].current", equalTo(false));
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void logoutOthers_terminatesEveryoneButCurrent_returns204() {
        when(currentSession.get()).thenReturn("s1");
        when(keycloak.listUserSessions("member-sub")).thenReturn(List.of(
            session("s1"), session("s2"), session("s3")
        ));

        given()
            .when().post("/api/sessions/logout-others")
            .then().statusCode(204);

        // s2 + s3 are terminated; the current session s1 is spared.
        verify(keycloak).logoutSession("s2");
        verify(keycloak).logoutSession("s3");
        verify(keycloak, never()).logoutSession("s1");
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void logoutOthers_currentUnidentifiable_is409_andTerminatesNothing() {
        // No sid -> we must NOT fall back to "log out all including me".
        when(currentSession.get()).thenReturn(null);
        when(keycloak.listUserSessions("member-sub")).thenReturn(List.of(
            session("s1"), session("s2")
        ));

        given()
            .when().post("/api/sessions/logout-others")
            .then().statusCode(409);

        verify(keycloak, never()).logoutSession("s1");
        verify(keycloak, never()).logoutSession("s2");
    }

    @Test
    void logoutOthers_unauthenticated_is401() {
        given().when().post("/api/sessions/logout-others").then().statusCode(401);
    }
}
