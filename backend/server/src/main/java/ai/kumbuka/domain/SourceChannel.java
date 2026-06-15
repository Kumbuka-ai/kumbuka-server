package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Where a memory came from. Server-derived from the request channel
 * (see ADR-0008): MCP tools mark their writes MCP, admin endpoints mark
 * theirs CONSOLE, the provisioning seeder marks its writes SYSTEM
 * (D-CORE-11). Callers do not get to choose — the channel is stamped by
 * whichever handler invokes {@code MemoryRepository.remember}, never
 * carried in a tool argument or DTO field.
 */
public enum SourceChannel {
    CONSOLE("console"),
    MCP("mcp"),
    /**
     * Server-derived seed identity (D-CORE-11). Used by the internal
     * /api/internal/seed-tenant endpoint to plant protected system-seed
     * mnemonics in a new tenant's global scope; not reachable from any
     * caller-facing surface. Pair: owner_subject is the system sentinel
     * (see {@link SystemSubject}).
     */
    SYSTEM("system");

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
            case "system"  -> SYSTEM;
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
