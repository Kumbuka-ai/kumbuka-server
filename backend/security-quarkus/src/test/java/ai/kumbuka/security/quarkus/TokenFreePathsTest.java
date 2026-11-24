// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The declared list: health and metrics and what lies below them, nothing else. */
class TokenFreePathsTest {

    @Test
    void health_and_metrics_and_their_sub_paths_are_on_the_list() {
        assertThat(TokenFreePaths.admits("/q/health")).isTrue();
        assertThat(TokenFreePaths.admits("/q/health/live")).isTrue();
        assertThat(TokenFreePaths.admits("/q/health/ready")).isTrue();
        assertThat(TokenFreePaths.admits("/q/metrics")).isTrue();
        assertThat(TokenFreePaths.admits("/q/metrics/json")).isTrue();
    }

    @Test
    void every_other_path_is_not() {
        assertThat(TokenFreePaths.admits("/q/healthz")).isFalse();
        assertThat(TokenFreePaths.admits("/q/metricsx")).isFalse();
        assertThat(TokenFreePaths.admits("/q/openapi")).isFalse();
        assertThat(TokenFreePaths.admits("/api/declaration")).isFalse();
        assertThat(TokenFreePaths.admits("/health")).isFalse();
        assertThat(TokenFreePaths.admits("/")).isFalse();
        assertThat(TokenFreePaths.admits("")).isFalse();
        assertThat(TokenFreePaths.admits(null)).isFalse();
    }
}
