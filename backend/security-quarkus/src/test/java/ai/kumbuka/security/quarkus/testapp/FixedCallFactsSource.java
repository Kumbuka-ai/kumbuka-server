// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.testapp;

import ai.kumbuka.security.contract.AuthenticationContext;
import ai.kumbuka.security.contract.CallFacts;
import ai.kumbuka.security.contract.CallFactsSource;
import ai.kumbuka.security.contract.PresentedCredential;
import ai.kumbuka.security.contract.ScopeGrant;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The test implementation of the facts interface: a fixed table from subject
 * to facts. A subject the table does not know has no facts.
 *
 * <ul>
 *   <li>{@code reader}: reads entries and lists, reads the tenant; may address
 *       {@code alpha} and {@code ghost} ({@code ghost} is a scope the test
 *       service does not have);</li>
 *   <li>{@code scopeless}: the same permissions, an empty scope set;</li>
 *   <li>{@code powerless}: no permission, may address {@code alpha};</li>
 *   <li>anybody else: no facts.</li>
 * </ul>
 */
@ApplicationScoped
public class FixedCallFactsSource implements CallFactsSource {

    public static final UUID TENANT = UUID.fromString("7a1c2b3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    private static final Set<String> READ = Set.of("test.entry.read", "test.entry.list", "test.tenant.read",
            "test.entry.unaddressed");
    private static final Set<ScopeGrant> ALPHA_AND_GHOST = Set.of(
            new ScopeGrant(UUID.fromString("11111111-1111-4111-8111-111111111111"), "alpha"),
            new ScopeGrant(UUID.fromString("22222222-2222-4222-8222-222222222222"), "ghost"));
    private static final Set<ScopeGrant> ALPHA = Set.of(
            new ScopeGrant(UUID.fromString("11111111-1111-4111-8111-111111111111"), "alpha"));

    private static final Map<String, CallFacts> TABLE = Map.of(
            "reader", facts("reader", READ, ALPHA_AND_GHOST),
            "scopeless", facts("scopeless", READ, Set.of()),
            "powerless", facts("powerless", Set.of(), ALPHA));

    private static CallFacts facts(String subject, Set<String> permissions, Set<ScopeGrant> scopes) {
        return new CallFacts(subject, Optional.empty(), Set.of("member"), permissions, scopes, TENANT, "rest",
                new AuthenticationContext("pwd", "basic", Instant.parse("2026-11-22T09:00:00Z")));
    }

    @Override
    public Optional<CallFacts> factsOf(PresentedCredential credential) {
        return Optional.ofNullable(TABLE.get(credential.principal()));
    }
}
