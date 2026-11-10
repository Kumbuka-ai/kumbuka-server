// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tag token grammar, without a database: what is accepted, what is refused,
 * and that a refusal names the field. Plain JUnit, so it runs without
 * containers.
 */
class TagTokenValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "a", "0", "bug", "component:api", "component:api-gateway", "v2", "a-", "a:",
        // 63 characters, the longest the grammar admits
        "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabc"})
    void acceptsTheContractGrammar(String token) {
        assertThatCode(() -> TagTokenValidator.validateToken(token)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "-a", ":a", "Bug", "BUG", "a b", "a_b", "a.b", "ä", "a\n", " a",
        // 64 characters, one too many
        "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcd"})
    void refusesAnythingElseWithTheFieldToken(String token) {
        assertThatThrownBy(() -> TagTokenValidator.validateToken(token))
            .isInstanceOfSatisfying(InvalidTagFieldException.class,
                e -> assertThat(e.field()).isEqualTo("token"));
    }

    @Test
    void refusesAMissingTokenWithTheFieldToken() {
        assertThatThrownBy(() -> TagTokenValidator.validateToken(null))
            .isInstanceOfSatisfying(InvalidTagFieldException.class,
                e -> assertThat(e.field()).isEqualTo("token"));
    }

    @Test
    void upperCaseIsRefusedNotFolded() {
        assertThatThrownBy(() -> TagTokenValidator.validateToken("Component:Api"))
            .isInstanceOf(InvalidTagFieldException.class)
            .hasMessageContaining("Component:Api");
    }

    @Test
    void descriptionIsOptionalAndBoundedAt500Characters() {
        assertThatCode(() -> TagTokenValidator.validateDescription(null)).doesNotThrowAnyException();
        assertThatCode(() -> TagTokenValidator.validateDescription("x".repeat(500)))
            .doesNotThrowAnyException();
        // Counted in characters, as char_length counts them: 500 umlauts pass.
        assertThatCode(() -> TagTokenValidator.validateDescription("ü".repeat(500)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> TagTokenValidator.validateDescription("x".repeat(501)))
            .isInstanceOfSatisfying(InvalidTagFieldException.class,
                e -> assertThat(e.field()).isEqualTo("description"));
    }

    /**
     * The application grammar and the DB CHECK must be the same string, or one
     * of them refuses what the other admits. Read from the migration itself, so
     * a change on either side breaks this test rather than a write in production.
     */
    @Test
    void theGrammarIsTheOneTheMigrationChecks() throws IOException {
        Path migration = Paths.get("src/main/resources/db/migration/V26__platform_tag_vocabulary.sql");
        if (!Files.exists(migration)) {
            migration = Paths.get("backend/server").resolve(migration);
        }
        String sql = Files.readString(migration);
        assertThat(sql).contains("CHECK (token ~ '" + TagTokenValidator.PATTERN.pattern() + "')");
        assertThat(sql).contains("char_length(description) <= " + TagTokenValidator.DESCRIPTION_MAX);
    }
}
