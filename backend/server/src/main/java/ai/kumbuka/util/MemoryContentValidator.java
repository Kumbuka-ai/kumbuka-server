package ai.kumbuka.util;

import jakarta.ws.rs.BadRequestException;

/**
 * Enforces the memory `content` length contract (≤ 1500 chars) on the server,
 * for BOTH write surfaces — the MCP tool and the admin REST API. The limit is a
 * documented part of the entry contract; before this it lived only in the
 * console form, so a direct API/MCP caller could persist unbounded content
 * (storage-abuse vector). The DB CHECK (V10) is the defence-in-depth backstop.
 *
 * <p>Length only: {@code null} is tolerated (required-ness is the DB {@code NOT
 * NULL} + the caller's concern; an update may pass null to preserve).
 */
public final class MemoryContentValidator {

    private MemoryContentValidator() {}

    public static final int MAX_LEN = 1500;

    /** @throws BadRequestException (HTTP 400 on the admin API; tool error on MCP)
     *          if {@code content} exceeds {@link #MAX_LEN}. */
    public static void validate(String content) {
        if (content != null && content.length() > MAX_LEN) {
            throw new BadRequestException(
                "content too long: max " + MAX_LEN + " characters (was " + content.length() + ")");
        }
    }
}
