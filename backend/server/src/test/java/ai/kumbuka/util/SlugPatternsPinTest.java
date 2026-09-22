package ai.kumbuka.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the canonical grammar string verbatim. This pattern is the
 * platform-wide contract for the scope slug — the console mirrors it in
 * TypeScript and the DB CHECK constraint backs it up — so any drift in the
 * pattern string must fail loud here, not surface as a mismatch between
 * layers. The namespaced-key grammar was pinned beside it until the memory
 * engine left the core; it went with the key it described.
 */
class SlugPatternsPinTest {

    @Test
    void slugPattern_isTheCanonicalKebabSlugGrammar_verbatim() {
        assertThat(SlugPatterns.SLUG.pattern())
            .isEqualTo("^[a-z0-9]++(?:-[a-z0-9]++)*+$");
    }

    @Test
    void scopeSlugValidator_referencesTheCanonicalSlugPattern() {
        assertThat(ScopeSlugValidator.PATTERN).isSameAs(SlugPatterns.SLUG);
    }
}
