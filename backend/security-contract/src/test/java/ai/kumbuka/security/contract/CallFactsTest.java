// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The facts of a call: the members the order lists, permissions of the form
 * {@code service.object.act}, and sets that cannot change after the check read them.
 */
class CallFactsTest {

    private static final UUID TENANT = UUID.fromString("5d2f8a0c-1b7e-4a39-8c4d-3e9a6b1f2c10");
    private static final AuthenticationContext AUTH =
            new AuthenticationContext("pwd", "urn:kumbuka:acr:basic", Instant.parse("2026-11-22T10:00:00Z"));
    private static final ScopeGrant ALPHA =
            new ScopeGrant(UUID.fromString("0b6c1e4e-6f1d-4c55-9a43-2f1f0f6f7a01"), "alpha");

    private static CallFacts facts(Optional<String> actor, Set<String> permissions) {
        return new CallFacts("subject-1", actor, Set.of("member"), permissions, Set.of(ALPHA),
                TENANT, "mcp", AUTH);
    }

    @Test
    void the_facts_carry_every_member_they_were_given() {
        CallFacts facts = facts(Optional.of("agent-7"), Set.of("memory.entry.read"));

        assertThat(facts.subject()).isEqualTo("subject-1");
        assertThat(facts.actor()).contains("agent-7");
        assertThat(facts.roleAnchors()).containsExactly("member");
        assertThat(facts.permissions()).containsExactly("memory.entry.read");
        assertThat(facts.scopes()).containsExactly(ALPHA);
        assertThat(facts.tenant()).isEqualTo(TENANT);
        assertThat(facts.channel()).isEqualTo("mcp");
        assertThat(facts.authentication()).isEqualTo(AUTH);
    }

    @Test
    void a_call_without_an_actor_carries_an_empty_actor() {
        assertThat(facts(Optional.empty(), Set.of("memory.entry.read")).actor()).isEmpty();
    }

    @Test
    void a_present_blank_actor_is_refused() {
        assertThatThrownBy(() -> facts(Optional.of(" "), Set.of("memory.entry.read")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_permission_outside_the_form_service_object_act_is_refused() {
        for (String malformed : Set.of("memory.entry", "memory.entry.read.all", "Memory.entry.read",
                "memory..read", "memory_entry.x.read", "memory.entry.read ")) {
            assertThatThrownBy(() -> facts(Optional.empty(), Set.of(malformed)))
                    .as(malformed)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void a_permission_of_the_form_service_object_act_is_accepted() {
        assertThat(facts(Optional.empty(), Set.of("work-list.open-item.re-open")).permissions())
                .containsExactly("work-list.open-item.re-open");
    }

    @Test
    void the_sets_do_not_change_after_construction() {
        Set<String> permissions = new HashSet<>(Set.of("memory.entry.read"));
        CallFacts facts = facts(Optional.empty(), permissions);

        permissions.add("memory.entry.write");

        assertThat(facts.permissions()).containsExactly("memory.entry.read");
        assertThatThrownBy(() -> facts.scopes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missing_members_are_refused() {
        assertThatThrownBy(() -> new CallFacts(null, Optional.empty(), Set.of(), Set.of(), Set.of(),
                TENANT, "mcp", AUTH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallFacts("s", null, Set.of(), Set.of(), Set.of(),
                TENANT, "mcp", AUTH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallFacts("s", Optional.empty(), Set.of(), Set.of(), null,
                TENANT, "mcp", AUTH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallFacts("s", Optional.empty(), Set.of(), Set.of(), Set.of(),
                null, "mcp", AUTH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallFacts("s", Optional.empty(), Set.of(), Set.of(), Set.of(),
                TENANT, " ", AUTH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallFacts("s", Optional.empty(), Set.of(), Set.of(), Set.of(),
                TENANT, "mcp", null)).isInstanceOf(NullPointerException.class);
    }
}
