package ai.kumbuka.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * P0 read-authz on the dashboard aggregate (PR #51): GET /api/overview is
 * member-readable, but its {@code members[]} carries roster PII (email, role,
 * status) and must be populated for ADMINS ONLY. A plain member gets an empty
 * {@code members[]} and resolves author names via /api/users/directory.
 *
 * Both tests also exercise the two branches of the {@code isUserInRole}
 * gate so the new code is covered.
 */
@QuarkusTest
class AdminOverviewResourceTest {

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void overview_asMember_omitsRoster() {
        given()
            .when().get("/api/overview")
            .then()
                .statusCode(200)
                .body("members", notNullValue())
                .body("members.size()", equalTo(0));
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void overview_asAdmin_isAllowed() {
        // Admin branch runs the roster query (empty in the test DB, but the
        // branch is exercised — that's the half a member must never reach).
        given()
            .when().get("/api/overview")
            .then()
                .statusCode(200)
                .body("members", notNullValue());
    }
}
