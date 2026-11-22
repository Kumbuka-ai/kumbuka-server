// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An element of the scope set carries the scope's id and slug, both mandatory, the slug not blank. */
class ScopeGrantTest {

    private static final UUID ID = UUID.fromString("0b6c1e4e-6f1d-4c55-9a43-2f1f0f6f7a01");

    @Test
    void a_grant_carries_id_and_slug() {
        ScopeGrant grant = new ScopeGrant(ID, "alpha");

        assertThat(grant.id()).isEqualTo(ID);
        assertThat(grant.slug()).isEqualTo("alpha");
    }

    @Test
    void a_grant_without_id_is_refused() {
        assertThatThrownBy(() -> new ScopeGrant(null, "alpha"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_grant_without_slug_is_refused() {
        assertThatThrownBy(() -> new ScopeGrant(ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_grant_with_a_blank_slug_is_refused() {
        assertThatThrownBy(() -> new ScopeGrant(ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopeGrant(ID, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
