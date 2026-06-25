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

    /**
     * Physical row id (ADR-0024 §6 surrogate {@code row_id}). Renamed from the
     * V1 {@code memory.id} in V16 — the value is preserved (A1.3 (3): old
     * {@code memory.id} retained as {@code row_id}). This is the addressable
     * handle the MCP / admin surfaces use; the cross-version identity is the
     * separate {@link #logicalId}. The Java field stays {@code id} so the
     * Panache {@code @Id} ergonomics and the unchanged wire shape carry over;
     * only the column name moved.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "row_id")
    public UUID id;

    /**
     * Entry identity across versions (ADR-0024 §2). Immutable, never reused, the
     * target of all references/relations. Freshly generated per entry on persist
     * (and per row on the V16 backfill). In CE Step 1 the head is the only
     * version, so {@code (logical_id) ↔ (row_id)} is 1:1; the EE history docks
     * onto this column (A1.2). NOT NULL — set in {@link #onCreate()}.
     */
    @Column(name = "logical_id", nullable = false)
    public UUID logicalId;

    /**
     * Denormalized {@code scope.kind == PRIVATE} (A1.3 (1a)). The discriminator
     * for the two scope-kind-differentiated partial unique indexes; invariant
     * over the row's lifetime (D-CORE-17 forbids crossing the private/shared
     * boundary), so the denormalization can never go stale. Derived from the
     * scope on persist; never set by callers.
     */
    @Column(name = "is_private", nullable = false)
    public boolean isPrivate;

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
     * Entry lock (ADR-0024 §13) — replaces the V12 boolean {@code protected}.
     * {@code SYSTEM} is the D-CORE-11 system-seed lock (set only by the SYSTEM
     * seeder); it blocks move / rename / delete across all surfaces, enforced at
     * the DB layer by the {@code memory_protected_delete_block} (DELETE) +
     * {@code memory_protected_update_block} (move/rename) triggers. {@code ADMIN}
     * is reserved (D-CORE-13, not enforced in CE). Never settable through any
     * user-facing surface — no {@code @ToolArg} on memory_remember, no field on
     * {@code AdminDtos.CreateEntryRequest}.
     */
    @Column(name = "lock", nullable = false)
    @Convert(converter = MemoryLock.JpaConverter.class)
    public MemoryLock lock = MemoryLock.NONE;

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
        // hold the system lock; non-SYSTEM rows must NOT carry the sentinel and
        // must NOT hold the system lock. Caller code is expected to enforce this
        // too — this is the last-line check.
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
            if (lock == MemoryLock.SYSTEM) {
                throw new IllegalStateException(
                    "memory.lock = system requires source = SYSTEM");
            }
        }
        if (lock == null) lock = MemoryLock.NONE;
        // ADR-0024 §2: a fresh logical identity per entry (1:1 with row_id in CE).
        if (logicalId == null) logicalId = UUID.randomUUID();
        // A1.3 (1a): is_private is derived from the scope kind, never client-set.
        isPrivate = scope != null && scope.kind == ScopeKind.PRIVATE;
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
