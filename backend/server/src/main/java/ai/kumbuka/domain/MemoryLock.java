package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Entry lock, mirrored from the DB CHECK on {@code memory.lock}.
 * Replaces the V12 boolean {@code protected} column:
 * <ul>
 * <li>{@code SYSTEM} — the system-seed lock (the former
 *       {@code protected = true}). Blocks move / rename / delete across all
 *       surfaces; only the SYSTEM seeder writes it.</li>
 * <li>{@code ADMIN} — reserved for the admin-lock; NOT enforced in
 *       CE Step 1 (no write path sets it yet).</li>
 *   <li>{@code NONE} — an ordinary, freely mutable row (the former
 *       {@code protected = false}).</li>
 * </ul>
 *
 * <p>{@code lock} is a Java reserved word, so the field on {@link Memory} is
 * typed by this enum and a converter maps it to/from the lowercase DB form,
 * mirroring {@link ScopeKind} / {@link SourceChannel}.
 */
public enum MemoryLock {
    SYSTEM("system"),
    ADMIN("admin"),
    NONE("none");

    private final String dbValue;

    MemoryLock(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static MemoryLock fromDb(String value) {
        return switch (value) {
            case "system" -> SYSTEM;
            case "admin"  -> ADMIN;
            case "none"   -> NONE;
            default -> throw new IllegalArgumentException("unknown lock: " + value);
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<MemoryLock, String> {
        @Override
        public String convertToDatabaseColumn(MemoryLock attribute) {
            return attribute == null ? null : attribute.dbValue();
        }
        @Override
        public MemoryLock convertToEntityAttribute(String dbData) {
            return dbData == null ? null : MemoryLock.fromDb(dbData);
        }
    }
}
