// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * How and when the caller behind a call authenticated.
 *
 * @param method          the authentication method, as the identity provider names it
 * @param contextClass    the authentication context class the authentication reached
 * @param authenticatedAt the moment of the authentication
 */
public record AuthenticationContext(String method, String contextClass, Instant authenticatedAt) {

    public AuthenticationContext {
        Objects.requireNonNull(method, "an authentication context names its method");
        Objects.requireNonNull(contextClass, "an authentication context names its class");
        Objects.requireNonNull(authenticatedAt, "an authentication context names its moment");
    }
}
