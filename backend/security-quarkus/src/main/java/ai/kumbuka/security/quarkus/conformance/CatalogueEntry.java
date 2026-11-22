// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.conformance;

import java.util.Objects;

/**
 * One row of a service's catalogue: a facade method, the permission it must
 * declare and what it must declare it addresses. The service writes its
 * catalogue in its test tree; the guard never derives a row from a facade.
 *
 * @param facade     the facade class
 * @param method     the name of the public method
 * @param permission the permission, of the form {@code service.object.act}
 * @param binding    what the method addresses
 */
public record CatalogueEntry(Class<?> facade, String method, String permission, ScopeBinding binding) {

    public CatalogueEntry {
        Objects.requireNonNull(facade, "a catalogue row names its facade");
        Objects.requireNonNull(method, "a catalogue row names its method");
        Objects.requireNonNull(permission, "a catalogue row names its permission");
        Objects.requireNonNull(binding, "a catalogue row names what the method addresses");
    }
}
