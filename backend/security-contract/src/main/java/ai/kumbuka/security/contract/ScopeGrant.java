// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * One element of the scope set a call may address: the scope's technical
 * identity and its slug, both mandatory. A facade addresses a scope by its
 * slug, so the check compares slugs; the identity travels along so that an
 * edition never has to resolve one from the other.
 *
 * @param id   the scope's technical identity
 * @param slug the scope's slug, never blank
 */
public record ScopeGrant(UUID id, String slug) {

    public ScopeGrant {
        Objects.requireNonNull(id, "a scope grant carries the scope's id");
        Objects.requireNonNull(slug, "a scope grant carries the scope's slug");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("a scope grant carries a slug that is not blank");
        }
    }
}
