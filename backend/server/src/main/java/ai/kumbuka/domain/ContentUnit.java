package ai.kumbuka.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.annotations.TenantId;
import ai.kumbuka.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapped superclass of every stored content unit: identity and versioning,
 * authorship and channel provenance, the privacy flag, the entry lock, the
 * two timestamps and the tenant axis. Subclasses contribute their content
 * shape (their own columns, table mapping and lifecycle hooks).
 *
 * <p>Deliberately NOT mapped — reserved database columns without a Java
 * field: {@code is_head}, {@code state}, {@code is_deleted},
 * {@code valid_from}, {@code valid_until}. They carry only their column
 * defaults; the partial unique indexes read them directly in SQL. Keeping
 * them unmapped keeps every ORM write path on the column defaults, so the
 * Java and SQL layers cannot drift apart. Do not "complete" the mapping.
 */
@MappedSuperclass
public abstract class ContentUnit extends PanacheEntityBase {

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

    /** Optional caller-provided key; upsert target when present. */
    @Column
    public String key;

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
     * {@code updatable = false}: the first-write channel by definition never
     * changes — the provenance of the last edit lives in {@link #updatedSource}.
     * Formerly a convention; now enforced by the mapping.
     */
    @Column(nullable = false, updatable = false)
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
}
