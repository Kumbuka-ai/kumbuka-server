package ai.kumbuka.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Locks down the path-only same-origin allow-list of {@link AuthLoginResource}.
 *
 * Two surfaces:
 *   1. The endpoint at /api/auth/login — verified via RestAssured. The
 *      @Authenticated gate is satisfied by @TestSecurity.
 *   2. The static {@code safeReturnTo} helper — the entire open-redirect
 *      attack surface. Exercised both via the endpoint AND directly so
 *      both call sites contribute to coverage.
 *
 * Marked @QuarkusTest so quarkus-jacoco records hits on the Quarkus-loaded
 * class (a plain JUnit test wouldn't show up in coverage — Quarkus reloads
 * the bytecode through its own classloader).
 */
@QuarkusTest
class AuthLoginResourceTest {

    @Test
    @TestSecurity(user = "u", roles = {"admin"})
    void endpoint_redirectsToReturnTo_when_safe() {
        given()
            .redirects().follow(false)
            .when().get("/api/auth/login?return_to=/scopes/personal")
            .then()
                .statusCode(303)
                .header("Location", equalTo("/scopes/personal"));
    }

    @Test
    @TestSecurity(user = "u", roles = {"admin"})
    void endpoint_fallsBackToRoot_when_returnToIsUnsafe() {
        given()
            .redirects().follow(false)
            .when().get("/api/auth/login?return_to=https://evil.example/x")
            .then()
                .statusCode(303)
                .header("Location", equalTo("/"));
    }

    @Test
    @TestSecurity(user = "u", roles = {"admin"})
    void endpoint_fallsBackToRoot_when_returnToOmitted() {
        given()
            .redirects().follow(false)
            .when().get("/api/auth/login")
            .then()
                .statusCode(303)
                .header("Location", equalTo("/"));
    }

    @Test
    void endpoint_unauthenticated_isBlocked() {
        // @Authenticated must trip — the resource is the only initiator of
        // the OIDC flow, never reached by anonymous browsers directly.
        given()
            .redirects().follow(false)
            .when().get("/api/auth/login?return_to=/")
            .then().statusCode(401);
    }

    // ---------- safeReturnTo static helper edge cases -----------------------

    @Test
    void safeReturnTo_nullAndBlankFallBackToRoot() {
        assertThat(AuthLoginResource.safeReturnTo(null)).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("")).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("   ")).isEqualTo("/");
    }

    @Test
    void safeReturnTo_schemeRelativeIsRejected() {
        // `//evil.example` would otherwise be a same-origin bypass in some browsers.
        assertThat(AuthLoginResource.safeReturnTo("//evil.example/path")).isEqualTo("/");
    }

    @Test
    void safeReturnTo_absoluteUrlsAreRejected() {
        assertThat(AuthLoginResource.safeReturnTo("https://evil.example/")).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("http://evil.example/")).isEqualTo("/");
        // The contains("://") guard catches javascript: and data: URIs even
        // though they don't start with a slash.
        assertThat(AuthLoginResource.safeReturnTo("javascript://alert(1)")).isEqualTo("/");
    }

    @Test
    void safeReturnTo_mustStartWithSingleSlash() {
        // Relative paths without a leading slash would resolve unexpectedly.
        assertThat(AuthLoginResource.safeReturnTo("scopes/personal")).isEqualTo("/");
    }

    @Test
    void safeReturnTo_simplePathPassesThroughUnchanged() {
        assertThat(AuthLoginResource.safeReturnTo("/")).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("/scopes")).isEqualTo("/scopes");
        assertThat(AuthLoginResource.safeReturnTo("/scopes/personal?q=foo"))
            .isEqualTo("/scopes/personal?q=foo");
    }
}
