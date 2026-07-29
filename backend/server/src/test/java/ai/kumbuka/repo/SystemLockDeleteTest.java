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

/**
 * Behaviour gate for the removed delete-block: a row carrying
 * {@code lock = 'system'} is now deletable through the ordinary delete path.
 *
 * <p>The prior schema carried a {@code BEFORE DELETE} trigger that raised on any
 * such row, making it structurally undeletable below the application layer. That
 * trigger and its function are dropped by {@code V20}, so this test deletes a
 * system-locked row and asserts it is gone. It is self-validating: were the
 * trigger still bound, the delete would raise instead of returning a count, and
 * this test would fail.
 *
 * <p>Red probe (run manually, then revert): recreate the trigger and its
 * function inside the test transaction with the pre-{@code V20} DDL —
 *
 * <pre>{@code
 *   em.createNativeQuery("CREATE OR REPLACE FUNCTION memory_block_protected_delete() "
 *       + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
 *       + "IF OLD.lock IN ('system','admin') THEN RAISE EXCEPTION 'memory row is protected' "
 *       + "USING ERRCODE = 'P0001'; END IF; RETURN OLD; END; $$").executeUpdate();
 *   em.createNativeQuery("CREATE TRIGGER memory_protected_delete_block BEFORE DELETE ON memory "
 *       + "FOR EACH ROW EXECUTE FUNCTION memory_block_protected_delete()").executeUpdate();
 * }</pre>
 *
 * before the {@code forget} call — the delete then raises P0001 and the
 * assertion below goes red, proving the drop is what makes the row deletable.
 *
 * <p>{@code @TestTransaction} rolls the row back, leaving nothing committed.
 */
@QuarkusTest
class SystemLockDeleteTest {

    static final String MEMBER = "33333333-3333-3333-3333-333333333333";

    @Inject MemoryRepository memories;
    @Inject ScopeRepository scopes;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void systemLockedRow_isDeletable_throughOrdinaryForget() {
        // Plant a system-locked global row BELOW the write seam — the shape the
        // old delete-block used to protect (a legacy seed row). The key is
        // deliberately NOT in the reserved namespace: the reserved-namespace
        // delete guard is a separate axis, so an ordinary key isolates the axis
        // this test is about — the lock column and the dropped DB trigger.
        Memory locked = new Memory();
        locked.ownerSubject = SystemSubject.SENTINEL;
        locked.scope = scopes.requireBySlug("global");
        locked.type = MemoryType.CONVENTION;
        locked.key = "legacy-seed.deletable-gate";
        locked.content = "content";
        locked.source = SourceChannel.SYSTEM;
        locked.lock = MemoryLock.SYSTEM;
        memories.persist(locked);
        assertThat(locked.lock).isEqualTo(MemoryLock.SYSTEM);

        // An ordinary shared forget (author-independent) removes it: the lock is
        // not a delete-block on the MCP forget path — there is no DB trigger.
        int deleted = memories.forget(MEMBER, "global", null, "legacy-seed.deletable-gate");
        assertThat(deleted).as("the system-locked row deletes through the ordinary path").isEqualTo(1);

        long remaining = em.createQuery(
                "select count(m) from Memory m where m.key = :k", Long.class)
            .setParameter("k", "legacy-seed.deletable-gate")
            .getSingleResult();
        assertThat(remaining).as("no system-locked row remains after the delete").isZero();
    }
}
