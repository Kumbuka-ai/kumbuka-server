package ai.kumbuka.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.annotations.TenantId;
import ai.kumbuka.tenancy.StringUuidConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirror of a Keycloak user. The Keycloak `sub` claim is the authoritative
 * identifier; this row caches email, display name, role, status, and the
 * last sign-in timestamp for fast lookups.
 */
@Entity
@Table(name = "user_account")
public class UserAccount extends PanacheEntityBase {

    public enum Role {
        MEMBER("member"),
        ADMIN("admin");

        private final String dbValue;

        Role(String dbValue) { this.dbValue = dbValue; }

        public String dbValue() { return dbValue; }

        public static Role fromDb(String value) {
            return switch (value) {
                case "member" -> MEMBER;
                case "admin"  -> ADMIN;
                default -> throw new IllegalArgumentException("unknown role: " + value);
            };
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** Tenant axis — auto-populated by Hibernate (ADR-0011). */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** Keycloak `sub` claim. */
    @Column(nullable = false)
    public String subject;

    @Column(nullable = false)
    public String email;

    /** Editable in-app; mirrored to Keycloak (firstName + lastName). */
    @Column(name = "display_name")
    public String displayName;

    @Column(nullable = false)
    public String role;

    @Column(nullable = false)
    @Convert(converter = UserStatus.JpaConverter.class)
    public UserStatus status;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    public Role getRole() { return Role.fromDb(role); }

    public void setRole(Role r) { this.role = r.dbValue(); }
}
