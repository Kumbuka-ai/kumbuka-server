package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Where a memory came from. Server-derived from the request channel
 * (see ADR-0008): MCP tools mark their writes MCP, admin endpoints mark
 * theirs CONSOLE. Callers do not get to choose.
 */
public enum SourceChannel {
    CONSOLE("console"),
    MCP("mcp");

    private final String dbValue;

    SourceChannel(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static SourceChannel fromDb(String value) {
        return switch (value) {
            case "console" -> CONSOLE;
            case "mcp"     -> MCP;
            default -> throw new IllegalArgumentException("unknown source: " + value);
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<SourceChannel, String> {
        @Override
        public String convertToDatabaseColumn(SourceChannel attribute) {
            return attribute == null ? null : attribute.dbValue();
        }
        @Override
        public SourceChannel convertToEntityAttribute(String dbData) {
            return dbData == null ? null : SourceChannel.fromDb(dbData);
        }
    }
}
