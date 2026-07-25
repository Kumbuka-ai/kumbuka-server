package ai.kumbuka.util;

/**
 * The reserved {@code system} key namespace.
 *
 * <p>A key is reserved when its <em>first</em> grammar segment is exactly
 * {@code system} — either the bare key {@code system}, or a key whose first
 * dot/hyphen-delimited segment is {@code system} ({@code system.foo},
 * {@code system-foo}, {@code system.how-to-kumbuka.reading}). A key whose first
 * segment merely begins with those letters ({@code systematic}) or that carries
 * {@code system} only in a later segment ({@code foo.system}) is NOT reserved.
 *
 * <p>Reserved keys may be written only through the server-derived system
 * channel; every caller-facing channel is rejected at the shared write seam.
 * This is the durable, row-independent key reservation: it holds whether or not
 * a built-in guidance entry currently exists under the key, so a member can
 * never claim a reserved key by writing it before (or after) the built-in
 * content is present.
 *
 * <p>The segment grammar matches {@link SlugPatterns#KEY}: segments are
 * {@code [a-z0-9]+} joined by single {@code .} or {@code -} separators. The
 * reservation only inspects the first separator boundary, so it is independent
 * of the rest of the key's validity — an invalid key is simply never written.
 */
public final class SystemKeyNamespace {

    private SystemKeyNamespace() {}

    /** The reserved root segment. */
    public static final String ROOT = "system";

    /**
     * True when {@code key} is in the reserved namespace: its first
     * dot/hyphen-delimited segment equals {@link #ROOT}. {@code null} and blank
     * are never reserved — a keyless write cannot collide with the namespace.
     *
     * <p>Boundary decisions, pinned by {@code SystemKeyNamespaceTest}:
     * {@code system}, {@code system.x} and {@code system-x} are reserved (the
     * first segment is {@code system}); {@code systematic} and {@code foo.system}
     * are not.
     */
    public static boolean isReserved(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        int firstSegmentEnd = 0;
        while (firstSegmentEnd < key.length()) {
            char c = key.charAt(firstSegmentEnd);
            if (c == '.' || c == '-') {
                break;
            }
            firstSegmentEnd++;
        }
        return firstSegmentEnd == ROOT.length()
            && key.regionMatches(0, ROOT, 0, ROOT.length());
    }
}
