package ai.kumbuka.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The mnemonic head row — the {@link ContentUnit} shape plus what makes a
 * memory a memory: its type, its content body and its scope. The lifecycle
 * hooks live here (not on the superclass) because they reach into
 * {@link #scope}.
 */
@Entity
@Table(name = "memory")
public class Memory extends ContentUnit {

    @ManyToOne(optional = false)
    @JoinColumn(name = "scope_id", nullable = false)
    public Scope scope;

    @Column(nullable = false)
    @Convert(converter = MemoryType.JpaConverter.class)
    public MemoryType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @PrePersist
    void onCreate() {
        if (source == null) {
            throw new IllegalStateException(
                "memory.source must be set explicitly before persist (MCP, CONSOLE, or SYSTEM)");
        }
        if (source == SourceChannel.UNKNOWN) {
            throw new IllegalStateException(
                "memory.source = UNKNOWN is the read-side sentinel for an unrecognised "
                + "stored value and must never be written back; set a concrete channel");
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
        // is_private is derived from the scope kind, never client-set.
        isPrivate = scope != null && scope.kind == ScopeKind.PRIVATE;
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        // CE is update-in-place: an in-place edit stamps updated_at.
        // Correct UiP behaviour (resolved by-design). updated_by /
        // updated_source are set explicitly by the write paths (the entity does
        // not know the acting subject/channel).
        updatedAt = Instant.now();
    }
}
