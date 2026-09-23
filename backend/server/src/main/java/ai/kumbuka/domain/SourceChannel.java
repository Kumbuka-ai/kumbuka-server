package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Where a memory came from. Server-derived from the request channel
 * (see ADR-0008): admin endpoints mark their writes CONSOLE.
 * {@code SYSTEM} is the server-derived identity the guidance overlay
 * stamps on its transient (never-persisted) entries — no caller-facing writer
 * emits it, and a persisting write carrying it is refused at the repository seam.
 * Callers do not get to choose — the channel is stamped by whichever handler
 * performs the write, never carried in a tool argument or DTO field.
 *
 * <p>Since the memory engine left this service, no handler here stamps
 * {@code MCP} and no entity here binds {@link JpaConverter}. Why the member
 * nevertheless stays is recorded on the member itself.
 */
public enum SourceChannel {
    CONSOLE("console"),
    /**
     * A bulk ingestion write. Recognised by the column CHECKs and this
     * enum so rows written through an ingesting binary read and re-write
     * cleanly here; no write path in this build emits it yet.
     */
    IMPORT("import"),
    /**
     * The service channel. Nothing in this service writes it any more, and
     * nothing here reads a row that could carry it: the tool surface that
     * stamped it left with the memory engine, no entity binds
     * {@link JpaConverter}, and the two columns whose CHECKs admit
     * {@code 'mcp'} — {@code memory.source} and {@code memory.updated_source},
     * widened in V12 and V19 — belong to a table this binary no longer reads.
     *
     * <p>It stays because this enum is part of the library surface the composed
     * stack consumes, not because the core still uses it. Measured in sprint
     * 188.17: {@code platform-app}'s {@code ScopeDirectoryFromCore.notLocked}
     * passes this member to {@code MemberWritePolicy.assertScopeWritable} to
     * make the per-scope check on the channel that surface is — deliberately,
     * to reproduce what {@code platform.scope_access} publishes as
     * {@code can_write}. Dropping the member here would not remove dead code;
     * it would break that build. So the decision is not this service's alone.
     */
    MCP("mcp"),
    /**
     * Server-derived system identity. The guidance overlay stamps it on its
     * transient, never-persisted entries so a recall result can mark a built-in
     * entry with {@code source: "system"}; no caller-facing surface emits it, and
     * a persisting write carrying it is refused at the repository write seam. Pair:
     * owner_subject is the system sentinel (see {@link SystemSubject}).
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
