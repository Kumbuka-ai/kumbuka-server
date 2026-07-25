package ai.kumbuka.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the reserved {@code system} key-namespace predicate in isolation — no
 * container, no database. The rule: a key is reserved iff its first
 * dot/hyphen-delimited segment is exactly {@code system}.
 */
class SystemKeyNamespaceTest {

    // ------------ reserved ---------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "system",                          // the bare reserved segment
        "system.foo",                      // one dot segment after system
        "system.how-to-kumbuka.reading",   // several segments
        "system.a.b.c",
        "system-x",                        // PINNED: a hyphen segment after system IS reserved
        "system-config.value"
    })
    void reservedKeys(String key) {
        assertThat(SystemKeyNamespace.isReserved(key))
            .as("key '%s' should be reserved", key)
            .isTrue();
    }

    // ------------ NOT reserved ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "systematic",                      // first segment merely starts with the letters
        "systemic",
        "systems",                         // plural — a different single segment
        "foo.system",                      // system only in a later segment
        "foo-system",
        "decision.d-ops-26",
        "convention.how-to-kumbuka.types"  // the legacy guidance keyspace — not reserved
    })
    void notReservedKeys(String key) {
        assertThat(SystemKeyNamespace.isReserved(key))
            .as("key '%s' should NOT be reserved", key)
            .isFalse();
    }

    // ------------ null / blank ----------------------------------------------

    @Test
    void nullKey_isNotReserved() {
        assertThat(SystemKeyNamespace.isReserved(null)).isFalse();
    }

    @Test
    void blankKey_isNotReserved() {
        assertThat(SystemKeyNamespace.isReserved("")).isFalse();
        assertThat(SystemKeyNamespace.isReserved("   ")).isFalse();
    }

    @Test
    void rootConstant_isSystem() {
        assertThat(SystemKeyNamespace.ROOT).isEqualTo("system");
    }
}
