package ai.kumbuka.util;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the scope-slug format contract ScopeSlugValidator enforces
 * server-side: lowercase kebab identifiers. The validator gives a clean
 * BadRequestException instead of letting the DB CHECK constraint bubble
 * up as a 500.
 */
class ScopeSlugValidatorTest {

    @Test
    void nullSlug_isRejected_slugIsNeverOptional() {
        assertThatThrownBy(() -> ScopeSlugValidator.validate(null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("required");
    }

    @Test
    void blankSlug_isRejected() {
        assertThatThrownBy(() -> ScopeSlugValidator.validate(""))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("required");
        assertThatThrownBy(() -> ScopeSlugValidator.validate("   "))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("required");
    }

    // ------------ valid slugs -----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "billing-platform",
        "a",
        "a1-b2",
        "global",
        "onboarding",
        "q3-planning",
        "a-b-c-d",
        "123",
    })
    void validSlugs_accepted(String slug) {
        assertThatCode(() -> ScopeSlugValidator.validate(slug))
            .as("slug '%s' should be accepted", slug)
            .doesNotThrowAnyException();
    }

    // ------------ invalid slugs ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "Billing",             // uppercase
        "a--b",                // doubled separator
        "-a",                  // leading separator
        "a-",                  // trailing separator
        "a.b",                 // dot is exclusive to the namespaced key grammar
        "a_b",                 // underscore
        "with spaces",
        "slash/slug",
        "über",                // non-ASCII
    })
    void invalidSlugs_rejected(String slug) {
        assertThatThrownBy(() -> ScopeSlugValidator.validate(slug))
            .as("slug '%s' should be rejected", slug)
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining(ScopeSlugValidator.DESCRIPTION);
    }
}
