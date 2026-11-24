// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

/**
 * Why a call was refused. Each reason names the declared permission of the
 * method that was called; a reason about a scope names the slug the call
 * addressed. A new reason is a compile error at every exhaustive switch.
 */
public sealed interface Refusal {

    /** The declared permission of the method the call reached. */
    String permission();

    /** The vocabulary value of the reason, as a log line names it. */
    String reason();

    /** No facts could be established for the call. */
    record NoFacts(String permission) implements Refusal {
        @Override
        public String reason() {
            return "no-facts";
        }
    }

    /** The facts do not hold the permission the method declares. */
    record PermissionMissing(String permission) implements Refusal {
        @Override
        public String reason() {
            return "permission-missing";
        }
    }

    /** The method addresses a scope and the call may address none. */
    record ScopeSetEmpty(String permission) implements Refusal {
        @Override
        public String reason() {
            return "scope-set-empty";
        }
    }

    /** The method addresses a scope and the call names none. */
    record ScopeAbsent(String permission) implements Refusal {
        @Override
        public String reason() {
            return "scope-absent";
        }
    }

    /** The scope the call addresses is not in the set it may address. */
    record ScopeOutsideSet(String permission, String scopeSlug) implements Refusal {
        @Override
        public String reason() {
            return "scope-outside-set";
        }
    }

    /**
     * The method does not declare what it addresses (a scope, the tenant or
     * nothing), so the check cannot decide and refuses.
     */
    record AddressUndeclared(String permission) implements Refusal {
        @Override
        public String reason() {
            return "address-undeclared";
        }
    }
}
