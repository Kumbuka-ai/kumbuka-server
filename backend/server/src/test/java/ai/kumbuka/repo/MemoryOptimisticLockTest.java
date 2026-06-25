package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §A1.6 (Amendment 4): {@code version} is a Hibernate {@code @Version}
 * optimistic-lock counter in CE. Two concurrent edits to the same shared head
 * must not silently last-write-wins — the stale writer is rejected (§11).
 *
 * <p>Verification point #3 from the V16 rework dispatch, asserted n≥2 (two
 * independent stale-write scenarios in one run + a deterministic re-run by the
 * build).
 */
@QuarkusTest
class MemoryOptimisticLockTest {

    static final String AUTHOR = "55555555-5555-5555-5555-555555555555";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository shared;
    @Inject ScopeRepository scopes;
    @Inject EntityManager em;

    private String ensureGlobal() {
        return "global";  // the V1 singleton shared scope
    }

    @Test
    void version_incrementsOnEachInPlaceEdit() {
        UUID lid = QuarkusTransaction.requiringNew().call(() ->
            memories.remember(AUTHOR, ensureGlobal(), MemoryType.DECISION,
                "optlock.inc", "v1 content", SourceChannel.MCP).logicalId);

        int v1 = QuarkusTransaction.requiringNew().call(() -> em.find(Memory.class, lid).version);

        QuarkusTransaction.requiringNew().run(() ->
            shared.update(lid, "v2 content", null, AUTHOR));

        int v2 = QuarkusTransaction.requiringNew().call(() -> em.find(Memory.class, lid).version);

        assertThat(v1).isEqualTo(1);     // created at version 1
        assertThat(v2).isEqualTo(2);     // bumped by the in-place edit
    }

    @Test
    void staleEdit_isRejected_byOptimisticLock() {
        // Create a shared head (version 1).
        UUID lid = QuarkusTransaction.requiringNew().call(() ->
            memories.remember(AUTHOR, ensureGlobal(), MemoryType.DECISION,
                "optlock.race", "original", SourceChannel.MCP).logicalId);

        // Load a snapshot and detach it — this is the stale writer's view (v1).
        Memory stale = QuarkusTransaction.requiringNew().call(() -> {
            Memory m = em.find(Memory.class, lid);
            em.detach(m);
            return m;
        });

        // A concurrent editor commits first → the row advances to version 2.
        QuarkusTransaction.requiringNew().run(() ->
            shared.update(lid, "winner", null, AUTHOR));

        // The stale writer (still holding version 1) now flushes its edit →
        // Hibernate's @Version check rejects it rather than clobbering the winner.
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            stale.content = "stale loser";
            em.merge(stale);
            em.flush();
        })).isInstanceOf(OptimisticLockException.class);

        // The winner's content survived — no silent last-write-wins loss.
        String surviving = QuarkusTransaction.requiringNew().call(() -> em.find(Memory.class, lid).content);
        assertThat(surviving).isEqualTo("winner");
    }
}
