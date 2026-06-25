package ai.kumbuka.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure coverage for the {@link MemoryLock} enum + its JPA converter (the V16
 * replacement for the boolean {@code protected} column). No Quarkus needed —
 * the mapping is a plain value translation.
 */
class MemoryLockTest {

    @Test
    void dbValue_roundTrips_forEveryConstant() {
        for (MemoryLock l : MemoryLock.values()) {
            assertThat(MemoryLock.fromDb(l.dbValue())).isEqualTo(l);
        }
    }

    @Test
    void dbValues_areTheLowercaseSpecTerms() {
        assertThat(MemoryLock.SYSTEM.dbValue()).isEqualTo("system");
        assertThat(MemoryLock.ADMIN.dbValue()).isEqualTo("admin");
        assertThat(MemoryLock.NONE.dbValue()).isEqualTo("none");
    }

    @Test
    void fromDb_rejectsUnknownValue() {
        assertThatThrownBy(() -> MemoryLock.fromDb("frozen"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("frozen");
    }

    @Test
    void converter_mapsBothDirections_includingNull() {
        MemoryLock.JpaConverter c = new MemoryLock.JpaConverter();
        assertThat(c.convertToDatabaseColumn(MemoryLock.SYSTEM)).isEqualTo("system");
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToEntityAttribute("admin")).isEqualTo(MemoryLock.ADMIN);
        assertThat(c.convertToEntityAttribute(null)).isNull();
    }
}
