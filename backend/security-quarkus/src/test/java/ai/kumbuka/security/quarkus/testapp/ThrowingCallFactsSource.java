// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.testapp;

import ai.kumbuka.security.contract.CallFacts;
import ai.kumbuka.security.contract.CallFactsSource;
import ai.kumbuka.security.contract.PresentedCredential;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/** An implementation of the facts interface that fails on every call, as a source whose database is down would. */
@ApplicationScoped
public class ThrowingCallFactsSource implements CallFactsSource {

    @Override
    public Optional<CallFacts> factsOf(PresentedCredential credential) {
        throw new IllegalStateException("the facts source is unavailable");
    }
}
