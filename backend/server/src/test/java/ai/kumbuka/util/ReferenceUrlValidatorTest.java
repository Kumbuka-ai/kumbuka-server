package ai.kumbuka.util;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch-complete test for the D-CORE-7 reference-URL guard. The URL is stored
 * as metadata and never fetched, so the contract is narrow: optional, absolute
 * http(s), no embedded credentials, bounded length.
 *
 * <p>{@code @QuarkusTest} is deliberate: quarkus-jacoco only records hits on
 * classes loaded through the Quarkus classloader, so a plain JUnit test would
 * pass without registering any coverage (same reason as KeycloakAdminServiceTest).
 */
@QuarkusTest
class ReferenceUrlValidatorTest {

    // ---- accepted ---------------------------------------------------------

    @Test
    void nullAndBlank_areAccepted_fieldIsOptional() {
        assertThatCode(() -> ReferenceUrlValidator.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> ReferenceUrlValidator.validate("")).doesNotThrowAnyException();
        assertThatCode(() -> ReferenceUrlValidator.validate("   ")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://example.com",
        "https://example.com/path?q=1#frag",
        "https://wiki.internal/adr/0007",
        "  https://example.com/trimmed  ", // trimmed before checks
    })
    void absoluteHttpUrls_areAccepted(String url) {
        assertThatCode(() -> ReferenceUrlValidator.validate(url)).doesNotThrowAnyException();
    }

    // ---- length -----------------------------------------------------------

    @Test
    void overlongUrl_isRejected() {
        String tooLong = "https://example.com/" + "a".repeat(2048);
        assertThatThrownBy(() -> ReferenceUrlValidator.validate(tooLong))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too long");
    }

    // ---- credentials ------------------------------------------------------

    @Test
    void basicAuthCredentials_areRejected() {
        assertThatThrownBy(() -> ReferenceUrlValidator.validate("https://user:pass@example.com/x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("credentials");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/x?token=abc",
        "https://example.com/x?password=abc",
        "https://example.com/x?passwd=abc",
        "https://example.com/x?secret=abc",
        "https://example.com/x?api_key=abc",
        "https://example.com/x?api-key=abc",
        "https://example.com/x?apikey=abc",
        "https://example.com/x?access_token=abc",
        "https://example.com/x?access-token=abc",
        "https://example.com/x?foo=1&token=abc",
    })
    void credentialBearingQueryParams_areRejected(String url) {
        assertThatThrownBy(() -> ReferenceUrlValidator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("credentials");
    }

    // ---- scheme / syntax --------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/x",
        "mailto:someone@example.com",
        "file:///etc/passwd",
        "example.com/no-scheme",
    })
    void nonHttpOrRelative_isRejected(String url) {
        assertThatThrownBy(() -> ReferenceUrlValidator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http(s)");
    }

    @Test
    void malformedUri_isRejected() {
        assertThatThrownBy(() -> ReferenceUrlValidator.validate("http://exa mple.com/ bad space"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a valid URL");
    }

    @Test
    void rejectionMessagesNeverEchoTheUrl() {
        // The message is a fixed string — it must not leak the (possibly
        // secret-bearing) URL back into logs or API responses.
        try {
            ReferenceUrlValidator.validate("https://user:s3cr3t@example.com/x");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).doesNotContain("s3cr3t");
        }
    }
}
