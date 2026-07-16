package ai.kumbuka.util;

import java.util.regex.Pattern;

/**
 * The two canonical identifier grammars, held in one place so every
 * validator compiles against the same pattern string:
 *
 * <ul>
 *   <li>{@link #KEY} — namespaced key: lowercase alphanumerics joined by
 *       single {@code .} or {@code -} separators (used by the memory key).</li>
 *   <li>{@link #SLUG} — kebab slug: lowercase alphanumerics joined by
 *       single {@code -} separators only (used by the scope slug).</li>
 * </ul>
 *
 * Both are anchored with no leading, trailing, or doubled separator. The
 * {@code .} separator is exclusive to {@link #KEY} — a slug never contains
 * dots.
 *
 * <p>The quantifiers are <b>possessive</b> ({@code ++}, {@code *+}) to
 * eliminate any backtracking on malformed inputs — Sonar's regex analyzer
 * flags the equivalent greedy form as a catastrophic-backtracking risk,
 * even though the accepted language is identical. Guard tests pin both
 * pattern strings verbatim so the grammar cannot drift silently.
 */
public final class SlugPatterns {

    private SlugPatterns() {}

    /**
     * Namespaced key: lowercase a-z + digits with single {@code .} or
     * {@code -} separators. Same shape the DB CHECK on the memory key
     * enforces as the defence-in-depth backstop.
     */
    public static final Pattern KEY =
        Pattern.compile("^[a-z0-9]++(?:[.\\-][a-z0-9]++)*+$");

    /**
     * Kebab slug: lowercase a-z + digits with single {@code -} separators.
     * The DB CHECK on the scope slug remains the looser backstop; this is
     * the authoritative application-side grammar.
     */
    public static final Pattern SLUG =
        Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");
}
