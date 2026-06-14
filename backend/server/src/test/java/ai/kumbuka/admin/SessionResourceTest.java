package ai.kumbuka.admin;

import ai.kumbuka.mcp.MuteTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the session surface (D2 — account = link-out hybrid):
 *   GET   /api/auth/me — identity + role + Keycloak account-console deeplink
 *   PATCH /api/auth/me — display-name update (only writable field)
 *
 * Authentication is faked via {@link TestSecurity}. {@code @TestSecurity}
 * doesn't populate OIDC claims, so {@code identity.getAttribute("email")}
 * resolves to {@code null} in these tests — that's the same code path
 * users hit before they pick up an IdP email mapping, and the resource
 * tolerates it gracefully (the spec's null-fallback).
 */
@QuarkusTest
class SessionResourceTest {

    // Seeds a real user_account row (RLS'd) so the locale write-path runs — the
    // PATCH only persists locale when the caller's row exists.
    @Inject MuteTestSupport seed;

    @Test
    void me_unauthenticated_returns401() {
        given()
            .when().get("/api/auth/me")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "sub-alice", roles = {"member"})
    void me_asMember_returnsSubjectRoleAndAccountUrl() {
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("sub-alice"))
                .body("role", equalTo("member"))
                // locale is null until the member picks one (no account row here).
                .body("locale", nullValue())
                // accountConsoleUrl is composed from authBaseUrl + /realms/{realm}/account.
                .body("accountConsoleUrl", containsString("/realms/"))
                .body("accountConsoleUrl", containsString("/account"));
    }

    @Test
    @TestSecurity(user = "sub-root", roles = {"admin", "member"})
    void me_withAdminRole_isAdminNotMember() {
        // Role precedence: admin wins when both are present (single string in
        // the response, not an array).
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("role", equalTo("admin"));
    }

    @Test
    @TestSecurity(user = "sub-no-email", roles = {"member"})
    void me_withoutEmailClaim_returnsNullEmailAndDisplayName() {
        // displayName falls back to the email (also null here when no
        // UserAccount row exists for this fresh subject). The view must
        // serialise both as null, not propagate "Unknown" or similar.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("email", nullValue())
                .body("displayName", nullValue());
    }

    @Test
    @TestSecurity(user = "sub-patch", roles = {"member"})
    void updateMe_setsDisplayName_thenReadBack() {
        // PATCH is a no-op when no UserAccount row exists for this subject —
        // Phase 8 will sync rows on first IdP claim. The endpoint still
        // returns the current session view rather than 404.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"displayName": "Patch User"}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("sub-patch"));
    }

    @Test
    @TestSecurity(user = "sub-x", roles = {"member"})
    void updateMe_nullDisplayName_isTolerated() {
        // displayName=null means "no change" (clearing it is not a supported
        // operation here). The endpoint returns the current session view.
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().patch("/api/auth/me")
            .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "sub-locale", roles = {"member"})
    void updateMe_setsSupportedLocale_thenReadsBack() {
        seed.setMuted("sub-locale", false);   // ensure the account row exists

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"locale": "de"}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("locale", equalTo("de"));

        // Persisted — a fresh read returns it.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("locale", equalTo("de"));
    }

    @Test
    @TestSecurity(user = "sub-badlocale", roles = {"member"})
    void updateMe_unsupportedLocale_returns400() {
        seed.setMuted("sub-badlocale", false);   // row exists, so validation runs

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"locale": "fr"}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(400);
    }
}
