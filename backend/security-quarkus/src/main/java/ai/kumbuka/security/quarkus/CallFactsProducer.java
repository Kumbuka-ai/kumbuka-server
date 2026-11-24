// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.CallFacts;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

import java.util.Optional;

/**
 * Produces the facts of the current call once per request, read from the
 * attribute {@link CallFactsAugmentor} attached to the call's identity.
 */
@ApplicationScoped
public class CallFactsProducer {

    @Produces
    @RequestScoped
    CurrentCallFacts currentCallFacts(SecurityIdentity identity) {
        return new CurrentCallFacts(factsOf(identity));
    }

    static Optional<CallFacts> factsOf(SecurityIdentity identity) {
        return identity.getAttribute(CallFactsAugmentor.FACTS_ATTRIBUTE) instanceof CallFacts facts
                ? Optional.of(facts)
                : Optional.empty();
    }
}
