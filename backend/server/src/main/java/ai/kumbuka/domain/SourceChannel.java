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
    /**
     * A bulk ingestion write. Recognised by the column CHECKs and this
     * enum so rows written through an ingesting binary read and re-write
     * cleanly here; no write path in this build emits it yet.
     */
    IMPORT("import"),
    MCP("mcp"),
    /**
     * Server-derived seed identity (D-CORE-11). Used by the internal
     * /api/internal/seed-tenant endpoint to plant protected system-seed
     * mnemonics in a new tenant's global scope; not reachable from any
     * caller-facing surface. Pair: owner_subject is the system sentinel
     * (see {@link SystemSubject}).
     */
    SYSTEM("system"),
    /**
     * Read-side sentinel for a stored channel value this binary does not
     * know — a row written by a newer binary must stay readable here, or a
     * single such row would break every list that contains it. Never
     * persisted, enforced twice: the persist-time guard in
     * {@code Memory.onCreate()} rejects it before the ORM, and the value
     * 'unknown' is absent from both column CHECKs, so any write that slips
     * past the guard fails structurally at the database. (The converter
     * cannot throw here: Hibernate runs it for every member at bootstrap
     * to render the implicit enum CHECK.)
     */
    UNKNOWN("unknown");

    private final String dbValue;

    SourceChannel(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /**
     * Tolerant on the read side: an unrecognised stored value maps to
     * {@link #UNKNOWN} instead of throwing, so reading a row written by a
     * newer binary cannot fail a whole listing. The write side stays
     * strict (see {@link JpaConverter#convertToDatabaseColumn}).
     */
    public static SourceChannel fromDb(String value) {
        return switch (value) {
            case "console" -> CONSOLE;
            case "import"  -> IMPORT;
            case "mcp"     -> MCP;
            case "system"  -> SYSTEM;
            default -> UNKNOWN;
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<SourceChannel, String> {
        /**
         * Deliberately does NOT throw on {@link #UNKNOWN}: Hibernate invokes
         * this method for every enum member while building its metadata (to
         * render the implicit enum CHECK), so a throwing converter fails the
         * application start, not the offending write. The sentinel maps to
         * 'unknown', which both column CHECKs reject — a stray write dies at
         * the database, loudly.
         */
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
