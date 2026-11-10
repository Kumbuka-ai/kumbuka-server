// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.util;

/**
 * A tag argument was refused before anything was read or written. Carries the
 * name of the offending field ({@code token} or {@code description}) so a
 * surface can report it as a field error rather than parse the message.
 */
public class InvalidTagFieldException extends RuntimeException {

    private final String field;

    public InvalidTagFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
