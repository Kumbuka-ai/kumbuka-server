package ai.kumbuka.util;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2E-06 — pin the {@code memory.key} format contract MemoryKeyValidator
 * enforces server-side. Same regex the V2 CHECK constraint enforces at
 * the DB layer; the validator gives a clean BadRequestException instead
 * of letting the constraint violation bubble up.
 */
class MemoryKeyValidatorTest {

    @Test
    void nullKey_isAllowed_optionalUpsertSlot() {
        assertThatCode(() -> MemoryKeyValidator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    void emptyKey_isRejected_distinctFromNull() {
        assertThatThrownBy(() -> MemoryKeyValidator.validate(""))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("empty");
    }

    // ------------ valid keys ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "a",
        "abc",
        "decision",
        "decision.d-ops-26",
        "status.beta-gate",
        "convention.how-to-kumbuka.types",
        "convention.how-to-kumbuka.writing",
        "convention.how-to-kumbuka.reading",
        "a.b.c.d.e.f",
        "x-y",
        "x.y-z",
        "abc123",
        "v1.0.0",          // semver-ish — dots + digits only is fine
        "open-question.write-confirmation-setting"
    })
    void validKeys_accepted(String key) {
        assertThatCode(() -> MemoryKeyValidator.validate(key))
            .as("key '%s' should be accepted", key)
            .doesNotThrowAnyException();
    }

    // ------------ invalid keys ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "test_1",              // underscore — the WORKLIST regression key
        "test_underscore",
        "UPPERCASE",
        "MixedCase",
        "weird/slashes",
        "with spaces",
        "with\ttab",
        "trailing-",
        "-leading",
        ".leading-dot",
        "trailing-dot.",
        "double..dot",
        "double--hyphen",      // not just "double" — adjacent separators
        ".",
        "-",
        "..",
        "key.UPPER",
        "key.with_under",
        "key/with/slashes",
        "key.with space",
        "öbject",              // non-ASCII
        "🚀"
    })
    void invalidKeys_rejected(String key) {
        assertThatThrownBy(() -> MemoryKeyValidator.validate(key))
            .as("key '%s' should be rejected", key)
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining(key);
    }

    @Test
    void errorMessage_carriesDescription() {
        assertThatThrownBy(() -> MemoryKeyValidator.validate("test_1"))
            .hasMessageContaining(MemoryKeyValidator.DESCRIPTION);
    }

    @Test
    void description_mentionsTheUnderscoreBan_explicitly() {
        assertThat(MemoryKeyValidator.DESCRIPTION).contains("underscore");
    }
}
