package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Scope kinds, mirrored from the DB CHECK constraint on `scope.kind`.
 * `private` is a Java keyword, so enum constants are upper-cased and a
 * converter maps them to/from the lowercase DB form.
 */
public enum ScopeKind {
    PRIVATE("private"),
    PROJECT("project"),
    GLOBAL("global");

    private final String dbValue;

    ScopeKind(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ScopeKind fromDb(String value) {
        return switch (value) {
            case "private" -> PRIVATE;
            case "project" -> PROJECT;
            case "global"  -> GLOBAL;
            default -> throw new IllegalArgumentException("unknown scope kind: " + value);
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<ScopeKind, String> {
        @Override
        public String convertToDatabaseColumn(ScopeKind attribute) {
            return attribute == null ? null : attribute.dbValue();
        }
        @Override
        public ScopeKind convertToEntityAttribute(String dbData) {
            return dbData == null ? null : ScopeKind.fromDb(dbData);
        }
    }
}
