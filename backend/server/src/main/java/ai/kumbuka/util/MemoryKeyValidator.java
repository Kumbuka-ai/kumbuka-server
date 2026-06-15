package ai.kumbuka.util;

import jakarta.ws.rs.BadRequestException;

import java.util.regex.Pattern;

/**
 * E2E-06 — Enforces the memory `key` format contract server-side:
 * lowercase alphanumeric with optional dot/kebab namespace separators.
 *
 * <p>Pre-validator the format was only described in the {@code @ToolArg}
 * doc string + the console editor; a direct API/MCP caller could persist
 * keys with underscores, uppercase, slashes — anything the column would
 * accept. That landed entries the console editor could no longer edit
 * (the underscore-key WORKLIST tech-debt regression).
 *
 * <p>Forced by the now-seeded {@code convention.how-to-kumbuka.writing}
 * mnemonic (D-CORE-11) which states the convention; with the seed
 * shipping in every tenant, the server must enforce it too.
 *
 * <p>{@code null} is allowed (key is optional — no upsert without it).
 * The DB CHECK (V14, same regex) is the defence-in-depth backstop.
 */
public final class MemoryKeyValidator {

    private MemoryKeyValidator() {}

    /**
     * Lowercase a-z + digits, with optional {@code .} or {@code -}
     * separators. Anchored — no leading/trailing separators, no doubles.
     * Same shape the DB CHECK in V2 + V14 enforces.
     */
    public static final Pattern PATTERN =
        Pattern.compile("^[a-z0-9]+([.\\-][a-z0-9]+)*$");

    /** Plain-language description, repeated in error messages + the @ToolArg doc. */
    public static final String DESCRIPTION =
        "lowercase, dot/kebab-namespaced (a-z, 0-9, '.', '-'); no underscores or uppercase";

    /** @throws BadRequestException when {@code key} is non-null and malformed. */
    public static void validate(String key) {
        if (key == null) return;                  // optional — no upsert without a key
        if (key.isEmpty()) {
            throw new BadRequestException("invalid key: empty string — pass null instead to skip upsert");
        }
        if (!PATTERN.matcher(key).matches()) {
            throw new BadRequestException(
                "invalid key '" + key + "': must be " + DESCRIPTION);
        }
    }
}
