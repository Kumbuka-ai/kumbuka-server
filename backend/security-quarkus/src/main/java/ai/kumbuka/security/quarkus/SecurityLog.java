// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.Refusal;
import org.jboss.logging.Logger;

/**
 * The binding's only logger. A line names the declared permission, the reason
 * and, for a scope outside the set, the slug the call addressed; it never
 * names the subject, the actor or anything else that identifies the caller,
 * and nothing here counts per identity.
 */
final class SecurityLog {

    private static final Logger LOG = Logger.getLogger("ai.kumbuka.security");

    private static final String SCOPE_OUTSIDE_SET =
            "refused: permission %s, scope slug %s, reason %s; answered as a scope that does not exist";
    private static final String REFUSED = "refused: permission %s, reason %s";

    private SecurityLog() {
    }

    /**
     * The one place the reason of a scope outside the set is written: the
     * caller is answered as for a scope that does not exist. WARN, because the
     * answer hides the reason from everybody but the operator.
     */
    static void scopeOutsideSet(Refusal.ScopeOutsideSet refusal) {
        LOG.warnf(SCOPE_OUTSIDE_SET, refusal.permission(), refusal.scopeSlug(), refusal.reason());
    }

    /** Every other refusal, which the caller is answered as a refused permission. Always {@code false}. */
    static boolean refused(Refusal refusal) {
        LOG.debugf(REFUSED, refusal.permission(), refusal.reason());
        return false;
    }
}
