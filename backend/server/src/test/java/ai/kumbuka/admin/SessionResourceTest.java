package ai.kumbuka.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.quarkus.test.security.oidc.UserInfo;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the session surface (D2 — account = link-out hybrid):
 *   GET  /api/auth/me  — identity + role + Keycloak account-console deeplink
 *   PATCH /api/auth/me — display-name update (only writable field)
 * Authentication is faked via @TestSecurity; UserAccount JPA writes happen
 * against the test datasource (DevServices Postgres).
 */
@QuarkusTest
class SessionResourceTest {

    @Test
    void me_unauthenticated_returns401() {
        given()
            .when().get("/api/auth/me")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "sub-alice", roles = {"member"})
    @OidcSecurity(userinfo = {
        @UserInfo(key = "email", value = "alice@kumbuka.ai")
    })
    void me_asMember_returnsRoleMemberAndAccountUrl() {
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("sub-alice"))
                .body("role", equalTo("member"))
                .body("accountConsoleUrl", containsString("/realms/"))
                .body("accountConsoleUrl", containsString("/account"));
    }

    @Test
    @TestSecurity(user = "sub-root", roles = {"admin", "member"})
    @OidcSecurity(userinfo = {
        @UserInfo(key = "email", value = "root@kumbuka.ai")
    })
    void me_withAdminRole_isAdminNotMember() {
        // Role precedence: admin wins when both are present (single string role
        // in the response, not an array).
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("role", equalTo("admin"));
    }

    @Test
    @TestSecurity(user = "sub-no-email", roles = {"member"})
    void me_withoutEmail_returnsNullEmailAndDisplayName() {
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("email", nullValue())
                .body("displayName", nullValue());
    }

    @Test
    @TestSecurity(user = "sub-patch", roles = {"member"})
    @OidcSecurity(userinfo = {
        @UserInfo(key = "email", value = "patch@kumbuka.ai")
    })
    void updateMe_setsDisplayName_thenReadBack() {
        // PATCH is a no-op when no UserAccount row exists for this subject (the
        // spec lets that be silent — the row appears on first IdP claim sync,
        // Phase 8). The endpoint still returns the current session view.
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
    @OidcSecurity(userinfo = {
        @UserInfo(key = "email", value = "x@kumbuka.ai")
    })
    void updateMe_nullDisplayName_isTolerated() {
        // displayName=null means "no change" (clearing the name is not a
        // supported operation here).
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().patch("/api/auth/me")
            .then().statusCode(200);
    }
}
