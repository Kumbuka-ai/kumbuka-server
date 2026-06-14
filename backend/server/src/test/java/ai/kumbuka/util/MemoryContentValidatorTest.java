package ai.kumbuka.util;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch-complete test for the F-1 content-length guard (≤ 1500 chars),
 * enforced server-side on both write surfaces (MCP + admin REST).
 *
 * <p>{@code @QuarkusTest} is deliberate: quarkus-jacoco only records hits on
 * classes loaded through the Quarkus classloader, so a plain JUnit test would
 * pass without registering any coverage (same reason as ReferenceUrlValidatorTest).
 */
@QuarkusTest
class MemoryContentValidatorTest {

    // ---- accepted ---------------------------------------------------------

    @Test
    void null_isAccepted_updateMayPreserve() {
        // null content is the "preserve existing" signal on update — the DB
        // NOT NULL + the caller enforce required-ness, not this length check.
        assertThatCode(() -> MemoryContentValidator.validate(null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 500, 991, 1499, 1500})
    void contentUpToTheLimit_isAccepted(int len) {
        String content = "a".repeat(len);
        assertThatCode(() -> MemoryContentValidator.validate(content)).doesNotThrowAnyException();
    }

    // ---- rejected ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1501, 2000, 10_000})
    void contentOverTheLimit_isRejected(int len) {
        String content = "a".repeat(len);
        assertThatThrownBy(() -> MemoryContentValidator.validate(content))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("content too long")
            .hasMessageContaining("max " + MemoryContentValidator.MAX_LEN);
    }

    @Test
    void boundaryIsExact_1500ok_1501rejected() {
        assertThatCode(() -> MemoryContentValidator.validate("a".repeat(1500))).doesNotThrowAnyException();
        assertThatThrownBy(() -> MemoryContentValidator.validate("a".repeat(1501)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectionMessageReportsTheActualLength() {
        try {
            MemoryContentValidator.validate("a".repeat(1600));
        } catch (BadRequestException e) {
            assertThat(e.getMessage()).contains("1600");
        }
    }
}
