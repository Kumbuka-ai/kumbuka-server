package ai.kumbuka.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The channel enum's read/write asymmetry: reads are tolerant (an
 * unrecognised stored value maps to the {@code UNKNOWN} sentinel instead of
 * throwing, so a row written by a newer binary cannot break a listing),
 * writes are strict (the sentinel is rejected before it could ever reach
 * the database).
 */
class SourceChannelTest {

    @Test
    void fromDb_unknownValue_mapsToSentinelInsteadOfThrowing() {
        assertThat(SourceChannel.fromDb("future-channel")).isEqualTo(SourceChannel.UNKNOWN);
        assertThat(SourceChannel.fromDb("")).isEqualTo(SourceChannel.UNKNOWN);
    }

    @Test
    void fromDb_roundTripsEveryConcreteChannel() {
        for (SourceChannel c : SourceChannel.values()) {
            if (c == SourceChannel.UNKNOWN) continue;
            assertThat(SourceChannel.fromDb(c.dbValue())).isEqualTo(c);
        }
    }

    @Test
    void importChannel_hasItsDbValue() {
        assertThat(SourceChannel.IMPORT.dbValue()).isEqualTo("import");
        assertThat(SourceChannel.fromDb("import")).isEqualTo(SourceChannel.IMPORT);
    }

    @Test
    void converter_mapsTheSentinelToAValueNoColumnCheckAccepts() {
        // The converter cannot throw on UNKNOWN (Hibernate runs it for every
        // member at bootstrap to render the implicit enum CHECK). Instead the
        // sentinel maps to 'unknown', a value absent from both column CHECKs,
        // so a stray write is rejected structurally at the database — the
        // companion database test proves that end.
        SourceChannel.JpaConverter converter = new SourceChannel.JpaConverter();
        assertThat(converter.convertToDatabaseColumn(SourceChannel.UNKNOWN)).isEqualTo("unknown");
    }

    @Test
    void converter_keepsNullPassthroughAndConcreteValues() {
        SourceChannel.JpaConverter converter = new SourceChannel.JpaConverter();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(SourceChannel.IMPORT)).isEqualTo("import");
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("future-channel")).isEqualTo(SourceChannel.UNKNOWN);
    }
}
