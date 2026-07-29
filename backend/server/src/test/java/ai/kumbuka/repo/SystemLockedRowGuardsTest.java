package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application-layer guards on a system-locked memory row, exercised against a
 * running database. Built-in guidance is no longer planted as table rows, but
 * the {@code lock} column and the server-derived system write channel remain, so
 * these guards still hold for any row that carries {@code lock = 'system'}:
 *
 * <ul>
 *   <li>the console / admin content-edit path refuses to update a locked row
 *       (the load-bearing application guard — there is no update trigger);</li>
 *   <li>an ordinary (unlocked) row still deletes through the normal path;</li>
 *   <li>only the system identity may own a system row (pre-persist invariant).</li>
 * </ul>
 *
 * <p>The delete side of a locked row — the MCP forget path deletes it, the
 * console single-delete refuses it — is characterised together in
 * {@link SystemLockDeleteTest}, where the two intended results sit side by side.
 * There is no delete-block below the application layer (the structural trigger
 * was dropped in V20); the console refusal is an application guard.
 *
 * <p>{@code @TestTransaction} rolls each method back, leaving no committed rows.
 */
@QuarkusTest
class SystemLockedRowGuardsTest {

    static final String MEMBER = "33333333-3333-3333-3333-333333333333";
    static final String ADMIN  = "44444444-4444-4444-4444-444444444444";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject ScopeRepository scopes;

    /** Plant a system-locked global row BELOW the write seam (direct persist) —
     *  the only way such a row exists now that the system channel no longer
     *  persists through the repository (it fails loud there). A locked row is
     *  planted directly, exactly as a legacy seed row sits in the table; the
     *  onCreate pair-invariant still requires source=SYSTEM + the sentinel owner. */
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
    void lockedRow_cannotBeUpdated_viaAdminConsolePath() {
        Memory locked = plantLockedRow("system.guard.update", "canonical content");
        // The console content-edit path is read-only for a locked row; this is
        // the load-bearing application guard. Red probe: drop the lock check in
        // SharedMemoryRepository.update and the update below succeeds instead of
        // throwing.
        assertThatThrownBy(() -> sharedMemories.update(locked.logicalId, "tampered", null, ADMIN))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.UPDATE_BLOCKED);
    }

    @Test
    @TestTransaction
    void ordinaryEntry_deletesThroughTheNormalPath() {
        Memory m = memories.remember(
            MEMBER, "global", MemoryType.GLOSSARY, "ordinary.delete.regression",
            "ordinary entry, no lock", SourceChannel.MCP);
        assertThat(m.lock).isEqualTo(MemoryLock.NONE);

        int deleted = memories.forget(MEMBER, "global", null, "ordinary.delete.regression");
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void plainMember_cannotClaimTheSystemSentinelAsOwner() {
        // The pre-persist invariant rejects a non-system row that claims the
        // system sentinel as its owner — the sentinel is the system writer's
        // identity, not a name any caller-facing channel can take.
        assertThatThrownBy(() -> {
            Memory m = new Memory();
            m.ownerSubject = SystemSubject.SENTINEL;
            m.scope = scopes.requireBySlug("private");
            m.type = MemoryType.GLOSSARY;
            m.content = "spoof — should never persist";
            m.source = SourceChannel.MCP;
            memories.persist(m);
        }).hasMessageContaining("reserved for SYSTEM writes");
    }
}
