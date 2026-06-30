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

    /**
     * D-CORE-2 mute write-state. When true, shared-scope writes are suspended
     * (console + MCP); private memory and all reads are unaffected. Admin-set,
     * reversible; orthogonal to role and status.
     */
    @Column(nullable = false)
    public Boolean muted = false;

    /**
     * The member's console UI language preference (e.g. {@code en}, {@code de}).
     * Server-side so the choice follows the member across devices; {@code null}
     * = unset (the console falls back to a cookie / the default).
     */
    @Column(name = "locale")
    public String locale;

    /**
     * D-CORE-10.1: onboarding-wizard state, per-user (keyed by KC sub). {@code
     * onboardingDismissed} once the owner ticks "don't show again" OR completes
     * the wizard (both reach the dismissed state); {@code onboardingLastStep} is
     * the resume point while still pending. Server-side so the dialog stays
     * dismissed across logins/devices (finding dogfood-15a). V15; defaults =
     * not-yet-dismissed, step 0.
     */
    @Column(name = "onboarding_dismissed", nullable = false)
    public Boolean onboardingDismissed = false;

    @Column(name = "onboarding_last_step", nullable = false)
    public Short onboardingLastStep = 0;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    /**
     * FEAT-13: the instant of this member's FIRST authenticated MCP request —
     * the beta-activation funnel's "first connection" step. Write-once: set once
     * by the {@code mcp} adapter while still {@code null}, never touched
     * thereafter; {@code null} = never connected over MCP. Write-once is the
     * structural guard — it can never become a "last seen" / activity log
     * (constraint.audit-no-activity-monitoring). The {@code mcp} in the name is a
     * known protocol-neutrality tension (the product aims protocol-agnostic),
     * flagged for a later rename; the SET logic lives in the mcp adapter, never
     * in the domain (constraint.protocol-neutrality).
     */
    @Column(name = "first_mcp_connected_at")
    public Instant firstMcpConnectedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    public Role getRole() { return Role.fromDb(role); }

    public void setRole(Role r) { this.role = r.dbValue(); }
}
