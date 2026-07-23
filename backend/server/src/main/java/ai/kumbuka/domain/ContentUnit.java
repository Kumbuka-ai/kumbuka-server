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
     * Entry identity across versions. Immutable, never reused; the target of
     * every reference and relation and the reference handle on the wire. The
     * database primary key is the composite {@code (logical_id, version)};
     * this build stores one row per {@code logical_id} with {@code version}
     * always 1, so Hibernate keys the entity by {@code logical_id} alone — a
     * build that stores multiple versions can promote the mapping to the
     * composite key additively, against the same unchanged database primary
     * key. Generated per entry on persist.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "logical_id")
    public UUID logicalId;

    /**
     * Version coordinate and optimistic-lock counter. {@code @Version}:
     * Hibernate increments it on each in-place edit and rejects a stale write
     * at flush with {@code OptimisticLockException} — concurrent-edit
     * protection in shared scopes. No wire token: the version travels inside
     * the server-side load→flush cycle. This build keeps no past-version
     * rows; the number climbs, superseded snapshots exist only in a build
     * that stores multiple versions.
     */
    @Version
    @Column(name = "version", nullable = false)
    public int version = 1;

    /**
     * Denormalized {@code scope.kind == PRIVATE}. The discriminator for the
     * two scope-kind-differentiated partial unique indexes; invariant over
     * the row's lifetime — an entry never crosses the private/shared
     * boundary — so the denormalization can never go stale. Derived from the
     * scope on persist; never set by callers.
     */
    @Column(name = "is_private", nullable = false)
    public boolean isPrivate;

    /**
     * Tenant axis. Auto-populated by Hibernate from the current
     * {@code TenantContext} at persist time; tenant-aware reads are
     * filtered automatically. Application code never assigns this.
     */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /**
     * First-author subject (Keycloak `sub`) — the creator. For private rows
     * this is the sole reader; for shared rows it records who created the
     * entry. <strong>Immutable creator authorship</strong>: never rewritten
     * on a later edit — the last-editor identity lives in {@link #updatedBy}.
     */
    @Column(name = "owner_subject", nullable = false)
    public String ownerSubject;

    /** Optional caller-provided key; upsert target when present. */
    @Column
    public String key;

    /**
     * Optional external provenance URL — structured metadata, never part of
     * the content body. Verify-on-demand: omitted from the load_context
     * digest and never auto-fetched. Credential-bearing URLs are rejected
     * (ReferenceUrlValidator + a DB CHECK). Nullable.
     */
    @Column(name = "reference", columnDefinition = "TEXT")
    public String reference;

    /**
     * Channel through which the row was <em>created</em>. Server-derived:
     * MCP tools set MCP, admin endpoints set CONSOLE, the seeder SYSTEM.
     * Symmetric to {@link #updatedSource} (channel of the last edit).
     * {@code updatable = false}: the first-write channel by definition never
     * changes — the provenance of the last edit lives in {@link #updatedSource}.
     * Formerly a convention; now enforced by the mapping.
     */
    @Column(nullable = false, updatable = false)
    @Convert(converter = SourceChannel.JpaConverter.class)
    public SourceChannel source;

    /**
     * Entry lock — replaces the earlier boolean {@code protected} column.
     * {@code SYSTEM} is the system-seed lock (set only by the SYSTEM seeder);
     * it blocks move / rename / delete. DELETE is enforced structurally by
     * the {@code memory_protected_delete_block} trigger; move/rename + edit
     * protection is application-layer — there is deliberately NO UPDATE
     * trigger. {@code ADMIN} is reserved; not enforced by this build. Never
     * settable through any user-facing surface.
     */
    @Column(name = "lock", nullable = false)
    @Convert(converter = MemoryLock.JpaConverter.class)
    public MemoryLock lock = MemoryLock.NONE;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * Last-editor subject (Keycloak `sub`) of the most recent in-place edit.
     * NULL on an entry never edited since creation. Distinct from
     * {@link #ownerSubject} (the immutable creator). Provenance, not activity
     * monitoring (one last-touch value per entry).
     */
    @Column(name = "updated_by")
    public String updatedBy;

    /**
     * Channel of the most recent in-place edit, symmetric to {@link #source}
     * (the create channel). NULL until the first edit.
     */
    @Column(name = "updated_source")
    @Convert(converter = SourceChannel.JpaConverter.class)
    public SourceChannel updatedSource;
}
