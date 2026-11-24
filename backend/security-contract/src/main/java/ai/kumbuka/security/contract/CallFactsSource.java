// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import java.util.Optional;

/**
 * The one interface through which an edition supplies the facts of the
 * current call. Each edition implements it once; a service embeds the
 * binding and one implementation, and no part of the contract or the binding
 * ships one.
 *
 * <p>The binding calls it once per authenticated call, off the event loop, so
 * an implementation may read a database.
 */
public interface CallFactsSource {

    /**
     * The facts of the call that presented {@code credential}.
     *
     * @param credential what the call presented, after its token was verified
     * @return the facts of the call; empty where they cannot be established,
     *         in which case every declared method refuses the call
     */
    Optional<CallFacts> factsOf(PresentedCredential credential);
}
