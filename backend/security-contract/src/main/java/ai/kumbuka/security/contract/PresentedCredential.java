// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import java.util.Map;
import java.util.Objects;

/**
 * What a call presented, after its token was verified: the principal and the
 * claims of the token, in plain Java values. A claim value is a
 * {@code String}, a {@code Boolean}, a {@code Long}, a
 * {@code java.math.BigDecimal}, a {@code java.util.List} of such values or a
 * {@code java.util.Map} from {@code String} to such values. A claim without a
 * value is absent from the map.
 *
 * @param principal the authenticated principal's name
 * @param claims    the claims of the verified token; empty where the call
 *                  presented no token claims
 */
public record PresentedCredential(String principal, Map<String, Object> claims) {

    public PresentedCredential {
        Objects.requireNonNull(principal, "a presented credential names its principal");
        claims = Map.copyOf(Objects.requireNonNull(claims, "a presented credential carries its claims"));
    }
}
