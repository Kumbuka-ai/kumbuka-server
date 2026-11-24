// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import java.util.Objects;
import java.util.Optional;

/**
 * The declared permission of a method that addresses a scope. The method
 * names the argument that carries the scope's slug:
 *
 * <pre>{@code
 * @PermissionsAllowed(value = "memory.entry.read", permission = ScopePermission.class, params = "scope")
 * public Entry read(String scope, String key) { ... }
 * }</pre>
 *
 * <p>Quarkus passes the named argument to the constructor parameter of the
 * same name, so the argument is named {@code scope}.
 */
public final class ScopePermission extends DeclaredPermission {

    private final String scope;

    /**
     * @param name  the declared permission
     * @param scope the slug the call addresses, as the method received it
     */
    public ScopePermission(String name, String scope) {
        super(name);
        this.scope = scope;
    }

    /** The slug the call addresses; empty where the call passed none. */
    public Optional<String> scope() {
        return Optional.ofNullable(scope).filter(s -> !s.isBlank());
    }

    @Override
    public String getActions() {
        return "scope";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScopePermission that
                && getName().equals(that.getName())
                && Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), scope);
    }
}
