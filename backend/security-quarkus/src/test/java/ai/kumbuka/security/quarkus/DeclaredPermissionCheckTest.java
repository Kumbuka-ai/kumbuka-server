// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.AuthenticationContext;
import ai.kumbuka.security.contract.CallFacts;
import ai.kumbuka.security.contract.Refusal;
import ai.kumbuka.security.contract.ScopeGrant;
import io.quarkus.security.StringPermission;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one check, decided without a running application. Each refusal the
 * order names is reached by exactly the facts that should reach it, and no
 * path admits a call the check cannot decide.
 */
class DeclaredPermissionCheckTest {

    private static final ScopeGrant ALPHA =
            new ScopeGrant(UUID.fromString("11111111-1111-4111-8111-111111111111"), "alpha");

    private static Optional<CallFacts> facts(Set<String> permissions, Set<ScopeGrant> scopes) {
        return Optional.of(new CallFacts("subject", Optional.empty(), Set.of(), permissions, scopes,
                UUID.fromString("7a1c2b3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), "rest",
                new AuthenticationContext("pwd", "basic", Instant.parse("2026-11-22T09:00:00Z"))));
    }

    @Test
    void the_declared_permission_on_a_scope_in_the_set_is_admitted() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of(ALPHA)),
                new ScopePermission("svc.entry.read", "alpha")))
                .isEqualTo(new Decision.Admitted());
    }

    @Test
    void no_facts_is_refused() {
        assertThat(DeclaredPermissionCheck.decide(Optional.empty(), new ScopePermission("svc.entry.read", "alpha")))
                .isEqualTo(new Decision.Refused(new Refusal.NoFacts("svc.entry.read")));
    }

    @Test
    void a_missing_permission_is_refused() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.list"), Set.of(ALPHA)),
                new ScopePermission("svc.entry.read", "alpha")))
                .isEqualTo(new Decision.Refused(new Refusal.PermissionMissing("svc.entry.read")));
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.list"), Set.of(ALPHA)),
                new ScopelessPermission("svc.entry.read", new String[] {"none"})))
                .isEqualTo(new Decision.Refused(new Refusal.PermissionMissing("svc.entry.read")));
    }

    @Test
    void an_empty_scope_set_is_refused_at_a_method_that_addresses_a_scope() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of()),
                new ScopePermission("svc.entry.read", "alpha")))
                .isEqualTo(new Decision.Refused(new Refusal.ScopeSetEmpty("svc.entry.read")));
    }

    @Test
    void a_call_that_names_no_scope_is_refused_at_a_method_that_addresses_one() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of(ALPHA)),
                new ScopePermission("svc.entry.read", null)))
                .isEqualTo(new Decision.Refused(new Refusal.ScopeAbsent("svc.entry.read")));
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of(ALPHA)),
                new ScopePermission("svc.entry.read", " ")))
                .isEqualTo(new Decision.Refused(new Refusal.ScopeAbsent("svc.entry.read")));
    }

    @Test
    void a_scope_outside_the_set_is_refused_naming_the_slug() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of(ALPHA)),
                new ScopePermission("svc.entry.read", "beta")))
                .isEqualTo(new Decision.Refused(new Refusal.ScopeOutsideSet("svc.entry.read", "beta")));
    }

    @Test
    void the_slug_is_compared_exactly() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of(ALPHA)),
                new ScopePermission("svc.entry.read", "Alpha")))
                .isEqualTo(new Decision.Refused(new Refusal.ScopeOutsideSet("svc.entry.read", "Alpha")));
    }

    @Test
    void a_scopeless_method_is_admitted_for_the_tenant_and_for_nothing_whatever_the_scope_set() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.tenant.read"), Set.of()),
                new ScopelessPermission("svc.tenant.read", new String[] {"tenant"})))
                .isEqualTo(new Decision.Admitted());
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.scope.list"), Set.of()),
                new ScopelessPermission("svc.scope.list", new String[] {"none"})))
                .isEqualTo(new Decision.Admitted());
    }

    @Test
    void a_scopeless_method_that_declares_no_address_is_refused() {
        for (String[] actions : new String[][] {null, {}, {"scope"}, {"tenant", "none"}, {"Tenant"}}) {
            assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.scope.list"), Set.of(ALPHA)),
                    new ScopelessPermission("svc.scope.list", actions)))
                    .isEqualTo(new Decision.Refused(new Refusal.AddressUndeclared("svc.scope.list")));
        }
    }

    @Test
    void a_permission_declared_outside_the_binding_is_refused() {
        assertThat(DeclaredPermissionCheck.decide(facts(Set.of("svc.entry.read"), Set.of(ALPHA)),
                new StringPermission("svc.entry.read")))
                .isEqualTo(new Decision.Refused(new Refusal.AddressUndeclared("svc.entry.read")));
    }

    @Test
    void the_checker_admits_refuses_and_answers_a_scope_outside_the_set_as_not_found() {
        var checker = DeclaredPermissionCheck.checkerFor(facts(Set.of("svc.entry.read"), Set.of(ALPHA)));

        assertThat(checker.apply(new ScopePermission("svc.entry.read", "alpha")).await().indefinitely()).isTrue();
        assertThat(checker.apply(new ScopePermission("svc.entry.write", "alpha")).await().indefinitely()).isFalse();
        assertThatThrownBy(() -> checker.apply(new ScopePermission("svc.entry.read", "beta")).await().indefinitely())
                .isInstanceOf(NotFoundException.class);
    }
}
