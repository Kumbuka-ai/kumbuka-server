package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Memory types, mirrored from the DB CHECK constraint on `memory.type`.
 */
public enum MemoryType {
    DECISION("decision"),
    CONVENTION("convention"),
    CONSTRAINT("constraint"),
    OPEN_QUESTION("open_question"),
    GLOSSARY("glossary"),
    STATUS("status");

    private final String dbValue;

    MemoryType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static MemoryType fromDb(String value) {
        return switch (value) {
            case "decision"      -> DECISION;
            case "convention"    -> CONVENTION;
            case "constraint"    -> CONSTRAINT;
            case "open_question" -> OPEN_QUESTION;
            case "glossary"      -> GLOSSARY;
            case "status"        -> STATUS;
            default -> throw new IllegalArgumentException("unknown memory type: " + value);
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<MemoryType, String> {
        @Override
        public String convertToDatabaseColumn(MemoryType attribute) {
            return attribute == null ? null : attribute.dbValue();
        }
        @Override
        public MemoryType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : MemoryType.fromDb(dbData);
        }
    }
}
