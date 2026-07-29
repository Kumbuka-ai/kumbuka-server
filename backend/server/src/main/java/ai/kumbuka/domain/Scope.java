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

@Entity
@Table(name = "scope")
public class Scope extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** Tenant axis — auto-populated by Hibernate (ADR-0011). */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** Immutable URL identity (kebab-case). See ADR-0007. */
    @Column(nullable = false)
    public String slug;

    @Column(nullable = false)
    public String name;

    @Column
    public String description;

    @Column(nullable = false)
    @Convert(converter = ScopeKind.JpaConverter.class)
    public ScopeKind kind;

    /** True for the singleton `global` scope. Cannot be archived or renamed. */
    @Column(nullable = false)
    public Boolean fixed;

    @Column(nullable = false)
    public Boolean archived;

    /**
     * read-only / frozen scope. Reserved in V16,
     * NOT enforced in CE Step 1 (enforcement logic + UI follow). Defaults false.
     */
    @Column(nullable = false)
    public Boolean locked = Boolean.FALSE;

    /** Keycloak `sub` of the user who created the scope. Null for system seeds. */
    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;
}
