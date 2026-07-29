package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Delete semantics of a system-locked memory row, across the two caller-facing
 * delete paths — held side by side, because the two results look contradictory
 * and are not. They are the ratified rule: a later well-meaning change that made
 * the two paths agree (blocking the forget, or resolving the console delete)
 * would be a regression, and one of these tests would turn red.
 *
 * <p>Same row state — a system-locked row whose key is deliberately NOT reserved
 * (a legacy seed row, the kind that can still sit in an existing tenant's table)
 * — addressed through two paths, with two intended results:
 *
 * <ul>
 *   <li><b>The MCP forget deletes it.</b> Deleting an entry from your own memory
 *       is not blocked by the lock. The lock is not an ownership barrier: the
 *       row belongs to the tenant, and a member removing an entry from their own
 *       memory is exactly what the surface is for. Blocking it would strand a
 *       legacy row behind an operator-only path and contradict the product's own
 *       built-in guidance. The reserved-namespace guard is a separate axis — it
 *       guards keys the tenant does not own — and this row's key is ordinary, so
 *       that guard does not fire; the lock is the only thing that could block the
 *       delete here, and it does not.</li>
 *   <li><b>The console single-delete refuses it.</b> On that path the lock does
 *       bind: it guards a curated row against an accidental deletion in the team
 *       console, where a delete control sits next to every row. Same lock,
 *       different purpose — a mis-click guard in the console, never a barrier to
 *       a member clearing their own memory over MCP.</li>
 * </ul>
 *
 * <p>Neither result rests on a database trigger. The prior schema carried a
 * {@code BEFORE DELETE} trigger that raised on any system-locked row, making it
 * structurally undeletable below the application layer; {@code V20} dropped it,
 * so the forget delete now reaches storage and the console refusal is an
 * application guard, not a database one.
 *
 * <p><b>Red probes</b> (run manually, then revert — the proof each result is
 * pinned, not incidental):
 * <ul>
 *   <li>the forget delete: add a lock check to {@link MemoryRepository#forget}
 *       (the tempting "fix" that would stop a member clearing their own locked
 *       row) and {@link #lockedRow_isDeletable_viaMcpForget} goes red; or
 *       recreate the dropped {@code BEFORE DELETE} trigger inside the test
 *       transaction with the pre-{@code V20} DDL and the delete raises P0001
 *       instead of returning a count.</li>
 *   <li>the console refusal: drop the lock check in
 *       {@link SharedMemoryRepository#deleteShared} and
 *       {@link #lockedRow_isRefused_byConsoleSingleDelete} returns 1 instead of
 *       throwing.</li>
 * </ul>
 *
 * <p>{@code @TestTransaction} rolls each row back, leaving nothing committed.
 */
@QuarkusTest
class SystemLockDeleteTest {

    static final String MEMBER = "33333333-3333-3333-3333-333333333333";

    /** One key, used by both paths, to make "same row state" literal: each test
     *  plants an identical row and rolls it back, so the two deletes address the
     *  same shape of row and differ only in the path. */
    static final String LOCKED_KEY = "legacy-seed.locked-row";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject ScopeRepository scopes;
    @Inject EntityManager em;

    /** Plant a system-locked global row BELOW the write seam (direct persist) —
     *  the only way such a row exists now that the system channel no longer
     *  persists through the repository (it fails loud there). The key is
     *  deliberately NOT in the reserved namespace, so the reserved-namespace
     *  guard (a separate axis) never fires and this isolates the lock axis. The
     *  onCreate pair-invariant still requires source=SYSTEM + the sentinel owner,
     *  exactly as a legacy seed row sits in the table. */
    private Memory plantLockedRow(String key, String content) {
        Memory m = new Memory();
        m.ownerSubject = SystemSubject.SENTINEL;
        m.scope = scopes.requireBySlug("global");
        m.type = MemoryType.CONVENTION;
        m.key = key;
        m.content = content;
        m.source = SourceChannel.SYSTEM;
        m.lock = MemoryLock.SYSTEM;
        memories.persist(m);
        assertThat(m.lock).isEqualTo(MemoryLock.SYSTEM);
        return m;
    }

    @Test
    @TestTransaction
    void lockedRow_isDeletable_viaMcpForget() {
        plantLockedRow(LOCKED_KEY, "content");

        // The MCP forget (author-independent shared delete) removes it: the lock
        // is not a delete-block on a member clearing their own memory.
        int deleted = memories.forget(MEMBER, "global", null, LOCKED_KEY);
        assertThat(deleted)
            .as("the system-locked row deletes through the member's own MCP forget")
            .isEqualTo(1);

        long remaining = em.createQuery(
                "select count(m) from Memory m where m.key = :k", Long.class)
            .setParameter("k", LOCKED_KEY)
            .getSingleResult();
        assertThat(remaining).as("no system-locked row remains after the delete").isZero();
    }

    @Test
    @TestTransaction
    void lockedRow_isRefused_byConsoleSingleDelete() {
        // The SAME row state as above — but addressed through the console single
        // delete, which is read-only for a locked row (the mis-click guard),
        // mirroring the content-edit's UPDATE_BLOCKED. The key is not reserved,
        // so it is the LOCK axis firing here, not the reserved-namespace guard.
        Memory locked = plantLockedRow(LOCKED_KEY, "content");
        assertThatThrownBy(() -> sharedMemories.deleteShared(locked.logicalId))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.UPDATE_BLOCKED);
        // The guard refused before any delete ran — the row is untouched.
        assertThat(sharedMemories.findSharedById(locked.logicalId)).isNotNull();
    }
}
