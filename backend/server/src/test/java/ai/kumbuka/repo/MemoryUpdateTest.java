package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The revision verb, exercised against a running database with the real
 * ORM/RLS weave (the mocked adapter cases live in {@code MemoryToolsTest}). The
 * non-creating counterpart to {@code remember}: it revises an existing row's
 * mutable fields and NEVER inserts, keeps the entry's identity and authorship
 * immutable, honours the private-owner rule, rejects locked/reserved rows, and
 * surfaces a concurrent stale edit as a typed conflict.
 *
 * <p>{@code @TestTransaction} rolls each method back, so the tests leave no
 * committed rows behind (the shared count-sensitive gates elsewhere stay green).
 */
@QuarkusTest
class MemoryUpdateTest {

    static final String OWNER = "44444444-4444-4444-4444-444444444444";
    static final String OTHER = "99999999-9999-9999-9999-999999999999";

    @Inject MemoryRepository memories;
    @Inject ScopeRepository scopes;
    @Inject EntityManager em;

    // ---------- mutable fields change; identity + authorship do not ----------

    @Test
    @TestTransaction
    void update_existingSharedEntry_changesMutableFields_keepsAuthorship() {
        Memory created = memories.remember(
            OWNER, "global", MemoryType.DECISION, "upd.basic", "v1", SourceChannel.MCP);
        created.reference = "https://example.com/first";
        UUID lid = created.logicalId;

        // A DIFFERENT author revises the shared head (author-independent), setting
        // content + type + clearing the reference (blank clears).
        Memory target = memories.findForUpdate(OTHER, "global", "upd.basic", null);
        assertThat(target).isNotNull();
        Memory revised = memories.updateEntry(
            OTHER, target, "v2", MemoryType.CONSTRAINT, "", true, SourceChannel.MCP);

        assertThat(revised.logicalId).isEqualTo(lid);          // same entry — no insert
        assertThat(revised.content).isEqualTo("v2");
        assertThat(revised.type).isEqualTo(MemoryType.CONSTRAINT);
        assertThat(revised.reference).isNull();                // blank cleared it
        // Immutable identity + first-author authorship:
        assertThat(revised.scope.slug).isEqualTo("global");
        assertThat(revised.key).isEqualTo("upd.basic");
        assertThat(revised.ownerSubject).isEqualTo(OWNER);     // creator unchanged
        assertThat(revised.source).isEqualTo(SourceChannel.MCP);
        // Last-editor provenance is stamped instead:
        assertThat(revised.updatedBy).isEqualTo(OTHER);
        assertThat(revised.updatedSource).isEqualTo(SourceChannel.MCP);
    }

    @Test
    @TestTransaction
    void update_referenceNotProvided_leavesReferenceUnchanged() {
        Memory created = memories.remember(
            OWNER, "global", MemoryType.DECISION, "upd.refkeep", "v1", SourceChannel.MCP);
        created.reference = "https://example.com/keep";

        Memory target = memories.findForUpdate(OWNER, "global", "upd.refkeep", null);
        // referenceProvided = false → reference is left as-is even though content changes.
        Memory revised = memories.updateEntry(
            OWNER, target, "v2", null, null, false, SourceChannel.MCP);

        assertThat(revised.content).isEqualTo("v2");
        assertThat(revised.reference).isEqualTo("https://example.com/keep");
    }

    // ---------- non-creation: an absent address never inserts ----------------

    @Test
    @TestTransaction
    void update_absentAddress_resolvesNull_andHasNoRow() {
        // The non-creation guarantee at the repository seam: findForUpdate returns
        // null for an address with no row, so the tool can only ever throw — there
        // is no code path from a null resolution to an insert. (The tool-level
        // not-found is pinned in MemoryToolsTest.update_absentTarget_throwsNotFound_
        // neverUpdates; removing that guard NPEs rather than inserting — the NULL probe.)
        assertThat(memories.findForUpdate(OWNER, "global", "upd.ghost-never-created", null))
            .isNull();
        assertThat(memories.find("scope.slug = ?1 and key = ?2", "global", "upd.ghost-never-created")
            .firstResultOptional()).isEmpty();
    }

    // ---------- private entries are owner-only, by key AND by id ------------

    @Test
    @TestTransaction
    void update_privateEntry_isResolvableOnlyByOwner() {
        Memory created = memories.remember(
            OWNER, "private", MemoryType.DECISION, "upd.priv", "secret", SourceChannel.MCP);
        UUID lid = created.logicalId;

        // Another user cannot resolve it for update — by (scope, key) or by id.
        assertThat(memories.findForUpdate(OTHER, "private", "upd.priv", null)).isNull();
        assertThat(memories.findForUpdate(OTHER, null, null, lid)).isNull();

        // The owner can, both ways.
        assertThat(memories.findForUpdate(OWNER, "private", "upd.priv", null)).isNotNull();
        assertThat(memories.findForUpdate(OWNER, null, null, lid)).isNotNull();
    }

    // ---------- locked rows are read-only on this path ----------------------

    @Test
    @TestTransaction
    void update_lockedRow_isRejected() {
        // A locked (system) row is read-only for a non-system caller. Plant it
        // BELOW the write seam (direct persist) — the system channel no longer
        // persists through the repository (it fails loud there); onCreate still
        // requires source=SYSTEM + the sentinel owner to carry a system lock.
        Memory seed = new Memory();
        seed.ownerSubject = ai.kumbuka.domain.SystemSubject.SENTINEL;
        seed.scope = scopes.requireBySlug("global");
        seed.type = MemoryType.CONVENTION;
        seed.key = "upd.locked";
        seed.content = "seed";
        seed.source = SourceChannel.SYSTEM;
        seed.lock = MemoryLock.SYSTEM;
        memories.persist(seed);
        assertThat(seed.lock).isEqualTo(MemoryLock.SYSTEM);

        Memory target = memories.findForUpdate(OWNER, "global", "upd.locked", null);
        assertThat(target).isNotNull();
        assertThatThrownBy(() ->
            memories.updateEntry(OWNER, target, "hijack", null, null, false, SourceChannel.MCP))
            .isInstanceOf(ProtectedEntryException.class);
    }

    // ---------- optimistic lock: reuse the existing @Version machinery -------

    @Test
    @TestTransaction
    void update_staleVersion_surfacesTypedConflict() {
        Memory created = memories.remember(
            OWNER, "global", MemoryType.DECISION, "upd.optlock", "orig", SourceChannel.MCP);
        UUID lid = created.logicalId;
        // Load the managed row (version 1), then let a concurrent commit advance its
        // version out from under us: the native bump changes only the version, so
        // updateEntry's forced flush issues UPDATE ... WHERE version = 1 that now
        // matches zero rows → Hibernate's optimistic-lock failure, which updateEntry
        // wraps into the typed StaleVersionException (the same machinery remember uses).
        Memory target = memories.findForUpdate(OWNER, "global", "upd.optlock", null);
        em.createNativeQuery("update memory set version = version + 1 where logical_id = ?1")
            .setParameter(1, lid)
            .executeUpdate();

        assertThatThrownBy(() ->
            memories.updateEntry(OWNER, target, "loser", null, null, false, SourceChannel.MCP))
            .isInstanceOf(MemoryRepository.StaleVersionException.class);
    }
}
