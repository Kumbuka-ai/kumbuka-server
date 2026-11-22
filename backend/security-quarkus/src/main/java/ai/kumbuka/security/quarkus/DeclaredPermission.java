// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import java.security.Permission;

/**
 * The permission a facade method declares through {@code @PermissionsAllowed}.
 * There are exactly two forms, and the form is what a method declares about
 * the address it acts on:
 *
 * <ul>
 *   <li>{@link ScopePermission}: the method addresses a scope, and names the
 *       argument that carries the scope's slug in {@code params};</li>
 *   <li>{@link ScopelessPermission}: the method addresses the tenant
 *       ({@code "service.object.act:tenant"}) or nothing
 *       ({@code "service.object.act:none"}).</li>
 * </ul>
 *
 * <p>Neither form decides anything. Quarkus builds one per call, and the one
 * check, {@link DeclaredPermissionCheck}, decides it against the facts of the
 * call. A declaration in any other form is refused by that check.
 */
public abstract sealed class DeclaredPermission extends Permission
        permits ScopePermission, ScopelessPermission {

    protected DeclaredPermission(String name) {
        super(name);
    }

    /**
     * Always {@code false}: a declared permission is never implied by another
     * permission object, it is decided against the facts of the call.
     */
    @Override
    public final boolean implies(Permission permission) {
        return false;
    }
}
