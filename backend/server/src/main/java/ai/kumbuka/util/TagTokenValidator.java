// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.util;

import java.util.regex.Pattern;

/**
 * Enforces the tag {@code token} and {@code description} contract
 * server-side, before a row is attempted, so an off-shape value is refused
 * with the field it concerns instead of surfacing as a CHECK violation. The DB
 * CHECKs on {@code platform.tag} remain the backstop and carry the same
 * grammar, verbatim.
 *
 * <p>Nothing is folded: an upper-case token is refused, never lower-cased —
 * a value that would change identity is rejected, not repaired.
 */
public final class TagTokenValidator {

    private TagTokenValidator() {}

    /** The token grammar, identical to the {@code tag_token_format} CHECK. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9:-]{0,62}$");

    /** The longest description accepted, in characters (code points). */
    public static final int DESCRIPTION_MAX = 500;

    /** Plain-language description, repeated in error messages. */
    public static final String DESCRIPTION =
        "1 to 63 characters of a-z, 0-9, ':' and '-', starting with a letter or digit";

    /** @throws InvalidTagFieldException with field {@code token} when it is null or malformed. */
    public static void validateToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new InvalidTagFieldException("token", "token is required");
        }
        if (!PATTERN.matcher(token).matches()) {
            throw new InvalidTagFieldException("token",
                "invalid token '" + token + "': must be " + DESCRIPTION);
        }
    }

    /** @throws InvalidTagFieldException with field {@code description} when it is too long. */
    public static void validateDescription(String description) {
        if (description != null
                && description.codePointCount(0, description.length()) > DESCRIPTION_MAX) {
            throw new InvalidTagFieldException("description",
                "description is longer than " + DESCRIPTION_MAX + " characters");
        }
    }
}
