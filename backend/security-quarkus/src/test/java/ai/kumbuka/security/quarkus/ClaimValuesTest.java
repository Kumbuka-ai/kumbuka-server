// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import jakarta.json.Json;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The claims of a token reach the facts interface as plain Java values, never as JSON types. */
class ClaimValuesTest {

    @Test
    void json_values_become_plain_values() {
        assertThat(ClaimValues.plain(Json.createValue("sub-1"))).contains("sub-1");
        assertThat(ClaimValues.plain(Json.createValue(42))).contains(42L);
        assertThat(ClaimValues.plain(Json.createValue(new BigDecimal("1.5")))).contains(new BigDecimal("1.5"));
        assertThat(ClaimValues.plain(JsonValue.TRUE)).contains(Boolean.TRUE);
        assertThat(ClaimValues.plain(JsonValue.FALSE)).contains(Boolean.FALSE);
        assertThat(ClaimValues.plain(JsonValue.NULL)).isEmpty();
        assertThat(ClaimValues.plain(null)).isEmpty();
    }

    @Test
    void json_objects_and_arrays_become_maps_and_lists() {
        JsonValue act = Json.createObjectBuilder()
                .add("sub", "agent-7")
                .add("scopes", Json.createArrayBuilder().add("alpha").add("beta"))
                .addNull("gone")
                .build();

        assertThat(ClaimValues.plain(act))
                .contains(Map.of("sub", "agent-7", "scopes", List.of("alpha", "beta")));
    }

    @Test
    void plain_values_stay_plain() {
        assertThat(ClaimValues.plain("x")).contains("x");
        assertThat(ClaimValues.plain(7)).contains(7L);
        assertThat(ClaimValues.plain(1_700_000_000L)).contains(1_700_000_000L);
        assertThat(ClaimValues.plain(Set.of("member"))).contains(List.of("member"));
    }
}
