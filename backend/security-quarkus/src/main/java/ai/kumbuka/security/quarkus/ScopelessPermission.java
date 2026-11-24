// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The declared permission of a method that addresses no scope. The method
 * says which of the two it is, after a colon, and there is no default:
 *
 * <pre>{@code
 * @PermissionsAllowed(value = "memory.tenant.export:tenant", permission = ScopelessPermission.class)
 * @PermissionsAllowed(value = "memory.scope.list:none", permission = ScopelessPermission.class)
 * }</pre>
 *
 * <p>A declaration without exactly one of the two leaves {@link #address()}
 * empty, and the check refuses every call to it.
 */
public final class ScopelessPermission extends DeclaredPermission {

    /** What a scopeless method addresses. */
    public enum Address {
        TENANT("tenant"),
        NONE("none");

        private final String declared;

        Address(String declared) {
            this.declared = declared;
        }

        /** The word a declaration writes after the colon. */
        public String declared() {
            return declared;
        }

        static Optional<Address> of(String[] actions) {
            if (actions == null || actions.length != 1) {
                return Optional.empty();
            }
            return Arrays.stream(values()).filter(a -> a.declared.equals(actions[0])).findFirst();
        }
    }

    private final String[] actions;

    /**
     * @param name    the declared permission
     * @param actions what the declaration wrote after the colon
     */
    public ScopelessPermission(String name, String[] actions) {
        super(name);
        this.actions = actions == null ? new String[0] : actions.clone();
    }

    /** What the method declares it addresses; empty where it declares neither or both. */
    public Optional<Address> address() {
        return Address.of(actions);
    }

    @Override
    public String getActions() {
        return String.join(",", actions);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScopelessPermission that
                && getName().equals(that.getName())
                && Arrays.equals(actions, that.actions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), Arrays.hashCode(actions));
    }
}
