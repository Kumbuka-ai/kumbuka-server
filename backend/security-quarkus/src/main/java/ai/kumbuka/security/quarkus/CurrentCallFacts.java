// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.CallFacts;

import java.util.Optional;

/**
 * The facts of the current call, as domain code injects them:
 *
 * <pre>{@code
 * @Inject CurrentCallFacts call;
 * ...
 * UUID tenant = call.facts().tenant();
 * }</pre>
 *
 * <p>Produced once per request by {@link CallFactsProducer}. A method that
 * passed {@link DeclaredPermissionCheck} always finds facts here, because the
 * check refuses a call without them.
 */
public class CurrentCallFacts {

    private final Optional<CallFacts> facts;

    CurrentCallFacts(Optional<CallFacts> facts) {
        this.facts = facts;
    }

    /**
     * The facts of the current call.
     *
     * @throws IllegalStateException where the call carries none, which only
     *         code that no declared permission guards can meet
     */
    public CallFacts facts() {
        return facts.orElseThrow(() -> new IllegalStateException(
                "the current call carries no facts; read them only behind a declared permission"));
    }

    /** The facts of the current call; empty where it carries none. */
    public Optional<CallFacts> find() {
        return facts;
    }
}
