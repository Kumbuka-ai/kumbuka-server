// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.conformance;

import java.util.Objects;

/** What a facade method addresses, as a catalogue row expects it. */
public sealed interface ScopeBinding {

    /** The method addresses a scope through the argument of this name. */
    record Scope(String argument) implements ScopeBinding {
        public Scope {
            Objects.requireNonNull(argument, "a scope binding names its argument");
        }
    }

    /** The method addresses the tenant. */
    record Tenant() implements ScopeBinding {
    }

    /** The method addresses nothing. */
    record None() implements ScopeBinding {
    }

    static ScopeBinding scope(String argument) {
        return new Scope(argument);
    }

    static ScopeBinding tenant() {
        return new Tenant();
    }

    static ScopeBinding none() {
        return new None();
    }
}
