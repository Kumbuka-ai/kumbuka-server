package ai.kumbuka.util;

import jakarta.ws.rs.BadRequestException;

import java.util.regex.Pattern;

/**
 * Enforces the scope {@code slug} format contract server-side: scope slugs
 * are lowercase kebab identifiers (see {@link SlugPatterns#SLUG}).
 *
 * <p>Mirrors {@link MemoryKeyValidator}: a clean 400 with a plain message
 * naming the accepted shape, instead of letting an off-shape slug fall
 * through to the DB CHECK constraint as an unmapped 500. The DB CHECK
 * remains the defence-in-depth backstop.
 *
 * <p>Unlike the memory key, a scope slug is never optional — {@code null}
 * or blank is rejected here too (callers may keep their own earlier blank
 * check for a friendlier combined message).
 */
public final class ScopeSlugValidator {

    private ScopeSlugValidator() {}

    /** Kebab slug grammar — {@link SlugPatterns#SLUG} is the single holder. */
    public static final Pattern PATTERN = SlugPatterns.SLUG;

    /** Plain-language description, repeated in error messages. */
    public static final String DESCRIPTION =
        "lowercase kebab (a-z, 0-9, '-'); no dots, underscores or uppercase";

    /** @throws BadRequestException when {@code slug} is null, blank, or malformed. */
    public static void validate(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new BadRequestException("slug is required");
        }
        if (!PATTERN.matcher(slug).matches()) {
            throw new BadRequestException(
                "invalid slug '" + slug + "': must be " + DESCRIPTION);
        }
    }
}
