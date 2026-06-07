package ai.kumbuka.admin;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The admin REST API may NEVER surface a private memory — by listing,
 * by addressing, or by aggregate count. This test exercises the HTTP
 * surface (RestAssured) and asserts the invariant after a private row
 * has been planted by another user.
 */
@QuarkusTest
class AdminPrivateInvariantTest {

    static final String OTHER_USER = "other-user-sub";

    @Inject MemoryRepository memories;

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void getScopes_doesNotIncludePrivate() {
        // Plant a private row for OTHER_USER (commits via the repo's own tx).
        memories.remember(OTHER_USER, "private", MemoryType.DECISION,
            "secret-key", "secret content", SourceChannel.MCP);

        given()
            .when().get("/api/scopes")
            .then()
                .statusCode(200)
                .body("kind", everyItem(not(is("private"))));
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void getEntries_underPrivateSlug_is404() {
        memories.remember(OTHER_USER, "private", MemoryType.DECISION,
            "secret", "content", SourceChannel.MCP);

        given()
            .when().get("/api/scopes/private/entries")
            .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin-sub", roles = {"admin"})
    void overview_recentActivity_doesNotExposePrivateScope() {
        memories.remember(OTHER_USER, "private", MemoryType.DECISION,
            "k1", "PRIVATE-MARKER content", SourceChannel.MCP);
        memories.remember(OTHER_USER, "global", MemoryType.DECISION,
            "k2", "global content here", SourceChannel.MCP);

        given()
            .when().get("/api/overview")
            .then()
                .statusCode(200)
                .body("recent.scopeSlug", everyItem(not(is("private"))));
    }

    @Test
    @TestSecurity(user = "member-sub", roles = {"member"})
    void createScope_asMember_isForbidden_whenPolicyIsAdminsOnly() {
        // Default seed: createScopes = 'admins'
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "mem-tries", "name": "Member Tries", "description": null}
                """)
            .when().post("/api/scopes")
            .then().statusCode(403);
    }
}
