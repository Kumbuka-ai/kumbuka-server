package ai.kumbuka.util;

import java.util.regex.Pattern;

/**
 * The canonical identifier grammar, held in one place so every validator
 * compiles against the same pattern string: {@link #SLUG} — a kebab slug,
 * lowercase alphanumerics joined by single {@code -} separators (used by the
 * scope slug). Anchored, with no leading, trailing, or doubled separator.
 *
 * <p>A second grammar, the dotted namespaced key, stood beside it until the
 * memory engine left the core: it existed for the entry key alone, so it went
 * with it.
 *
 * <p>The quantifiers are <b>possessive</b> ({@code ++}, {@code *+}) to
 * eliminate any backtracking on malformed inputs — Sonar's regex analyzer
 * flags the equivalent greedy form as a catastrophic-backtracking risk,
 * even though the accepted language is identical. A guard test pins the
 * pattern string verbatim so the grammar cannot drift silently.
 */
public final class SlugPatterns {

    private SlugPatterns() {}

    /**
     * Kebab slug: lowercase a-z + digits with single {@code -} separators.
     * The DB CHECK on the scope slug remains the looser backstop; this is
     * the authoritative application-side grammar.
     */
    public static final Pattern SLUG =
        Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");
}
