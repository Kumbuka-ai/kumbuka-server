package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Lifecycle status of a team member, mirrored from Keycloak.
 *   - ACTIVE  : verified, enabled, can sign in
 *   - INVITED : created in Keycloak but has not yet completed enrolment
 *               (no password set, enrolment email pending)
 *   - DISABLED: revoked sign-in; row retained for audit
 */
public enum UserStatus {
    ACTIVE("active"),
    INVITED("invited"),
    DISABLED("disabled");

    private final String dbValue;

    UserStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static UserStatus fromDb(String value) {
        return switch (value) {
            case "active"   -> ACTIVE;
            case "invited"  -> INVITED;
            case "disabled" -> DISABLED;
            default -> throw new IllegalArgumentException("unknown user status: " + value);
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<UserStatus, String> {
        @Override
        public String convertToDatabaseColumn(UserStatus attribute) {
            return attribute == null ? null : attribute.dbValue();
        }
        @Override
        public UserStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : UserStatus.fromDb(dbData);
        }
    }
}
