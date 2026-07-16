package ai.kumbuka.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two canonical grammar strings verbatim. These patterns are the
 * platform-wide contract for identifier shapes — the console mirrors them
 * in TypeScript and the DB CHECK constraints back them up — so any drift
 * in the pattern string must fail loud here, not surface as a mismatch
 * between layers.
 */
class SlugPatternsPinTest {

    @Test
    void keyPattern_isTheCanonicalNamespacedKeyGrammar_verbatim() {
        assertThat(SlugPatterns.KEY.pattern())
            .isEqualTo("^[a-z0-9]++(?:[.\\-][a-z0-9]++)*+$");
    }

    @Test
    void slugPattern_isTheCanonicalKebabSlugGrammar_verbatim() {
        assertThat(SlugPatterns.SLUG.pattern())
            .isEqualTo("^[a-z0-9]++(?:-[a-z0-9]++)*+$");
    }

    @Test
    void memoryKeyValidator_referencesTheCanonicalKeyPattern() {
        assertThat(MemoryKeyValidator.PATTERN).isSameAs(SlugPatterns.KEY);
    }

    @Test
    void scopeSlugValidator_referencesTheCanonicalSlugPattern() {
        assertThat(ScopeSlugValidator.PATTERN).isSameAs(SlugPatterns.SLUG);
    }
}
