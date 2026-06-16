package ai.kumbuka.domain;

import ai.kumbuka.tenancy.StringUuidConverter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only tenant-side governance audit (D-OPS-16 / D-CORE-9 substrate).
 * Records shared-governance events under the acting team-admin's KC {@code sub}
 * — distinct from the provider's {@code ops.provider_audit}. Never carries
 * memory content; erasure writes counts only.
 *
 * <p>Insert-only by construction: the {@code governance_audit_no_update/_delete}
 * triggers (V14) reject any mutation below the application layer.
 */
@Entity
@Table(name = "governance_audit")
public class GovernanceAudit extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** Tenant axis — auto-populated by Hibernate (ADR-0011). */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** KC {@code sub} of the acting admin. */
    @Column(name = "actor_subject", nullable = false)
    public String actorSubject;

    @Column(nullable = false)
    public String action;

    /** KC {@code sub} of the affected member (null for non-member actions). */
    @Column(name = "target_subject")
    public String targetSubject;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;
}
