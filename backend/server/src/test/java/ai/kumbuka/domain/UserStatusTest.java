package ai.kumbuka.domain;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UserStatus enum + JpaConverter — every fromDb branch + the JPA converter's
 * null handling. Marked @QuarkusTest so quarkus-jacoco sees the bytecode (a
 * plain JUnit test wouldn't be instrumented under the Quarkus classloader).
 */
@QuarkusTest
class UserStatusTest {

    @Test
    void fromDb_active_invited_disabled() {
        assertThat(UserStatus.fromDb("active")).isEqualTo(UserStatus.ACTIVE);
        assertThat(UserStatus.fromDb("invited")).isEqualTo(UserStatus.INVITED);
        assertThat(UserStatus.fromDb("disabled")).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void fromDb_unknownString_throws() {
        // Catches schema drift early — a new DB value without an enum case must
        // surface as IllegalArgumentException, not as a silent null.
        assertThatThrownBy(() -> UserStatus.fromDb("banned"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("banned");
    }

    @Test
    void dbValue_roundTripsThroughFromDb() {
        for (UserStatus s : UserStatus.values()) {
            assertThat(UserStatus.fromDb(s.dbValue())).isEqualTo(s);
        }
    }

    @Test
    void jpaConverter_attributeToColumn_andBack() {
        UserStatus.JpaConverter conv = new UserStatus.JpaConverter();

        assertThat(conv.convertToDatabaseColumn(UserStatus.ACTIVE)).isEqualTo("active");
        assertThat(conv.convertToDatabaseColumn(UserStatus.INVITED)).isEqualTo("invited");
        assertThat(conv.convertToDatabaseColumn(UserStatus.DISABLED)).isEqualTo("disabled");

        assertThat(conv.convertToEntityAttribute("active")).isEqualTo(UserStatus.ACTIVE);
        assertThat(conv.convertToEntityAttribute("invited")).isEqualTo(UserStatus.INVITED);
        assertThat(conv.convertToEntityAttribute("disabled")).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void jpaConverter_handlesNullSafely() {
        UserStatus.JpaConverter conv = new UserStatus.JpaConverter();
        // Null both ways — the column can be NULL on legacy rows.
        assertThat(conv.convertToDatabaseColumn(null)).isNull();
        assertThat(conv.convertToEntityAttribute(null)).isNull();
    }
}
