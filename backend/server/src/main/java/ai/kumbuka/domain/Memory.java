package ai.kumbuka.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.annotations.TenantId;
import ai.kumbuka.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory")
public class Memory extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /**
     * Tenant axis (ADR-0011). Auto-populated by Hibernate from the
     * current {@code TenantContext} at persist time; tenant-aware reads
     * are filtered automatically. Application code never assigns this.
     */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /**
     * Author/owner subject (Keycloak `sub`). For private rows this is the
     * sole reader; for shared rows it records who wrote the entry.
     */
    @Column(name = "owner_subject", nullable = false)
    public String ownerSubject;

    @ManyToOne(optional = false)
    @JoinColumn(name = "scope_id", nullable = false)
    public Scope scope;

    @Column(nullable = false)
    @Convert(converter = MemoryType.JpaConverter.class)
    public MemoryType type;

    /** Optional caller-provided key; upsert target when present. */
    @Column
    public String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    /**
     * Optional external provenance URL (D-CORE-7) — structured metadata, never
     * part of the content body. Verify-on-demand: omitted from the load_context
     * digest and never auto-fetched. Credential-bearing URLs are rejected
     * (ReferenceUrlValidator + a DB CHECK). Nullable.
     */
    @Column(name = "reference", columnDefinition = "TEXT")
    public String reference;

    /**
     * Channel through which the row was created. Server-derived per
     * ADR-0008: MCP tools set MCP, admin endpoints set CONSOLE. Never
     * defaulted at this layer — the caller-side service is expected to
     * set it explicitly.
     */
    @Column(nullable = false)
    @Convert(converter = SourceChannel.JpaConverter.class)
    public SourceChannel source;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (source == null) {
            throw new IllegalStateException(
                "memory.source must be set explicitly before persist (MCP or CONSOLE)");
        }
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
