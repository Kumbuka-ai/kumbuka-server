// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.conformance;

/**
 * One deviation the guard reports.
 *
 * @param facade the facade class the deviation concerns
 * @param method the method name
 * @param kind   what deviates
 * @param detail what was found against what the catalogue expects
 */
public record Offence(Class<?> facade, String method, Kind kind, String detail) {

    /** The deviations the guard exists to report. */
    public enum Kind {
        /** A public facade method declares no permission. */
        UNDECLARED,
        /** A public facade method has no row in the catalogue. */
        NOT_IN_CATALOGUE,
        /** The method declares its permission other than through exactly one declared permission of the binding. */
        FOREIGN_DECLARATION,
        /** The method declares another permission than its row. */
        WRONG_PERMISSION,
        /** The method declares no address, or another one than its row. */
        WRONG_SCOPE_BINDING,
        /** A catalogue row names a method no facade has. */
        ROW_WITHOUT_METHOD
    }

    @Override
    public String toString() {
        return facade.getSimpleName() + "#" + method + ": " + kind + " - " + detail;
    }
}
