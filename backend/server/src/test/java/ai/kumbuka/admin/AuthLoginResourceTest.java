package ai.kumbuka.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the path-only same-origin allow-list of {@link AuthLoginResource}.
 * The endpoint itself is exercised end-to-end by the OIDC flow in Phase 11; here
 * we lock down the static {@code safeReturnTo} helper, which is the entire
 * open-redirect attack surface.
 */
class AuthLoginResourceTest {

    @Test
    void nullAndBlankFallBackToRoot() {
        assertThat(AuthLoginResource.safeReturnTo(null)).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("")).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("   ")).isEqualTo("/");
    }

    @Test
    void schemeRelativeIsRejected() {
        // `//evil.example` would otherwise be a same-origin bypass in some browsers.
        assertThat(AuthLoginResource.safeReturnTo("//evil.example/path")).isEqualTo("/");
    }

    @Test
    void absoluteUrlsAreRejected() {
        assertThat(AuthLoginResource.safeReturnTo("https://evil.example/")).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("http://evil.example/")).isEqualTo("/");
        // The contains("://") guard catches javascript: and data: URIs even
        // though they don't start with a slash either.
        assertThat(AuthLoginResource.safeReturnTo("javascript://alert(1)")).isEqualTo("/");
    }

    @Test
    void mustStartWithSingleSlash() {
        // Relative paths without a leading slash would resolve unexpectedly.
        assertThat(AuthLoginResource.safeReturnTo("scopes/personal")).isEqualTo("/");
    }

    @Test
    void simplePathPassesThroughUnchanged() {
        assertThat(AuthLoginResource.safeReturnTo("/")).isEqualTo("/");
        assertThat(AuthLoginResource.safeReturnTo("/scopes")).isEqualTo("/scopes");
        assertThat(AuthLoginResource.safeReturnTo("/scopes/personal?q=foo")).isEqualTo("/scopes/personal?q=foo");
    }
}
