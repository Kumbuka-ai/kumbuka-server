// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.Refusal;

/** What {@link DeclaredPermissionCheck} decided about one call. */
public sealed interface Decision {

    /** The call holds the declared permission and addresses a scope it may address. */
    record Admitted() implements Decision {
    }

    /** The call is refused, for the reason it carries. */
    record Refused(Refusal refusal) implements Decision {
    }
}
