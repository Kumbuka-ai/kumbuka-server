// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import java.util.List;

/**
 * THE DECLARED LIST of paths a call may reach without a token: the endpoints
 * of SmallRye Health and of Micrometer under the default non-application root
 * {@code /q}. Every other path refuses an anonymous call
 * ({@link TokenRequiredPolicy}).
 *
 * <p>A path is on the list when it equals an entry or lies below one; a path
 * that merely begins with the same letters ({@code /q/healthz}) is not. A
 * service that moves the non-application root moves its health and metrics
 * endpoints off this list, and they then require a token like every other
 * path.
 */
public final class TokenFreePaths {

    /** The entries of the list, each with everything below it. */
    public static final List<String> ENTRIES = List.of("/q/health", "/q/metrics");

    private TokenFreePaths() {
    }

    /** Whether {@code path}, already normalised, is on the list. */
    public static boolean admits(String path) {
        if (path == null) {
            return false;
        }
        for (String entry : ENTRIES) {
            if (path.equals(entry) || path.startsWith(entry + "/")) {
                return true;
            }
        }
        return false;
    }
}
