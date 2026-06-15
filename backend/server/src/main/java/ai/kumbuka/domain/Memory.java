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

    /**
     * D-CORE-11: protected system-seed flag. Set true only by the
     * provisioning seeder ({@code SourceChannel.SYSTEM}); structurally
     * undeletable at the DB layer via the {@code memory_protected_delete_block}
     * trigger. Never settable through any user-facing surface — no
     * {@code @ToolArg} on memory_remember, no field on
     * {@code AdminDtos.CreateEntryRequest}.
     */
    @Column(name = "protected", nullable = false)
    public boolean protected_ = false;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (source == null) {
            throw new IllegalStateException(
                "memory.source must be set explicitly before persist (MCP, CONSOLE, or SYSTEM)");
        }
        // Pair invariant: SYSTEM rows must carry the system sentinel and may
        // be protected; non-SYSTEM rows must NOT carry the sentinel and must
        // NOT be protected. Caller code is expected to enforce this too —
        // this is the last-line check.
        if (source == SourceChannel.SYSTEM) {
            if (!SystemSubject.isSystem(ownerSubject)) {
                throw new IllegalStateException(
                    "SYSTEM source requires owner_subject = " + SystemSubject.SENTINEL);
            }
        } else {
            if (SystemSubject.isSystem(ownerSubject)) {
                throw new IllegalStateException(
                    "owner_subject = " + SystemSubject.SENTINEL + " is reserved for SYSTEM writes");
            }
            if (protected_) {
                throw new IllegalStateException(
                    "memory.protected = true requires source = SYSTEM");
            }
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
