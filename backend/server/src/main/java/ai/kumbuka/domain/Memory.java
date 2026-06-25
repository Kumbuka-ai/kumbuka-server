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
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory")
public class Memory extends PanacheEntityBase {

    /**
     * Entry identity across versions (ADR-0024 §2, Amendment 3). Immutable,
     * never reused, the target of all references/relations and the wire/MCP
     * reference handle (§8). The DB primary key is the composite
     * {@code (logical_id, version)}; CE is head-only (one row per logical_id,
     * {@code version} always 1), so Hibernate keys the entity by
     * {@code logical_id} alone — the EE step promotes the JPA mapping to a
     * composite key additively, against the same unchanged DB PK (Amendment 3
     * §A3.2/§A3.3). Freshly generated per entry on persist; the V16 backfill
     * stamps a fresh UUID per existing row.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "logical_id")
    public UUID logicalId;

    /**
     * Version coordinate (ADR-0024 §6) + CE optimistic-lock counter (§A1.6,
     * Amendment 4). {@code @Version}: Hibernate increments it on each in-place
     * edit and rejects a stale write at flush with {@code OptimisticLockException}
     * — concurrent-edit protection in shared scopes (§11), active in CE. No wire
     * token (constraint.protocol-neutrality): the version travels inside the
     * server-side load→flush cycle. CE keeps no past-version rows; the number
     * climbs, the snapshots are EE.
     */
    @Version
    @Column(name = "version", nullable = false)
    public int version = 1;

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
     * First-author subject (Keycloak `sub`) — the v1 creator. For private rows
     * this is the sole reader; for shared rows it records who created the entry.
     * <strong>Immutable creator authorship</strong>: never rewritten on a later
     * edit (Amendment 4 — the last-editor identity lives in {@link #updatedBy}).
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
     * Channel through which the row was <em>created</em> (ADR-0008). Server-
     * derived: MCP tools set MCP, admin endpoints set CONSOLE, the seeder SYSTEM.
     * Symmetric to {@link #updatedSource} (channel of the last edit).
     */
    @Column(nullable = false)
    @Convert(converter = SourceChannel.JpaConverter.class)
    public SourceChannel source;

    /**
     * Entry lock (ADR-0024 §13) — replaces the V12 boolean {@code protected}.
     * {@code SYSTEM} is the D-CORE-11 system-seed lock (set only by the SYSTEM
     * seeder); it blocks move / rename / delete. DELETE is enforced structurally
     * by the {@code memory_protected_delete_block} trigger; move/rename + edit
     * protection is application-layer (Amendment 2 — there is NO UPDATE trigger).
     * {@code ADMIN} is reserved (D-CORE-13, not enforced in CE). Never settable
     * through any user-facing surface.
     */
    @Column(name = "lock", nullable = false)
    @Convert(converter = MemoryLock.JpaConverter.class)
    public MemoryLock lock = MemoryLock.NONE;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * Last-editor subject (Keycloak `sub`) of the most recent in-place edit
     * (Amendment 4). NULL on an entry never edited since creation. Distinct from
     * {@link #ownerSubject} (the immutable v1 creator). Provenance, not activity
     * monitoring (one last-touch value per entry).
     */
    @Column(name = "updated_by")
    public String updatedBy;

    /**
     * Channel (`console | mcp | system`) of the most recent in-place edit
     * (Amendment 4), symmetric to {@link #source} (the create channel). NULL
     * until the first edit.
     */
    @Column(name = "updated_source")
    @Convert(converter = SourceChannel.JpaConverter.class)
    public SourceChannel updatedSource;

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
        // A1.3 (1a): is_private is derived from the scope kind, never client-set.
        isPrivate = scope != null && scope.kind == ScopeKind.PRIVATE;
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        // CE is update-in-place (Amendment 4): an in-place edit stamps updated_at.
        // Correct UiP behaviour (dogfood-22 resolved by-design). updated_by /
        // updated_source are set explicitly by the write paths (the entity does
        // not know the acting subject/channel).
        updatedAt = Instant.now();
    }
}
