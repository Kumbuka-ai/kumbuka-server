package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coexistence gate. When a tenant already carries the bundled guidance rows,
 * the overlay must not double them: the real row wins its key and the overlay
 * entry is suppressed. This is the property that makes the additive change a
 * null behaviour change on any already-seeded tenant.
 *
 * <p>Writes a real row under each guidance key inside the rolled-back
 * transaction, then asserts exactly one entry per guidance key on both read
 * chokepoints — the real row wins its key and the overlay entry for that key is
 * suppressed. The guidance keys now live in the reserved {@code system}
 * namespace, so an ordinary service write ({@code remember}/{@code createShared})
 * is refused by the reserved-namespace guard: the colliding row can only be
 * created BELOW the service seam, which is what {@link #plantCuratorRows}
 * does with a direct Panache persist. The rule is therefore unreachable via any
 * ordinary write path and kept purely as defence in depth; this test plants the
 * row beneath the guard so the suppression is still exercised.
 */
@QuarkusTest
class GuidanceCoexistenceTest {

    static final String SUBJECT = "66666666-6666-6666-6666-666666666666";

    private static final List<String> GUIDANCE_KEYS = List.of(
        "system.how-to-kumbuka.types",
        "system.how-to-kumbuka.writing",
        "system.how-to-kumbuka.reading");

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject ScopeRepository scopes;

    @Test
    @TestTransaction
    void afterCuratorRows_recallOnGlobal_returnsExactlyOnePerGuidanceKey() {
        plantCuratorRows();
        List<Memory> rows = memories.recall(SUBJECT, "global", MemoryType.CONVENTION, null, false);
        assertExactlyOnePerGuidanceKey(rows);
    }

    @Test
    @TestTransaction
    void afterCuratorRows_listSharedOnGlobal_returnsExactlyOnePerGuidanceKey() {
        plantCuratorRows();
        List<Memory> rows = sharedMemories.listShared("global", null);
        // NULL PROBE anchor: removing the "row wins" suppression makes each
        // guidance key appear twice (real row + overlay) and this fails.
        assertExactlyOnePerGuidanceKey(rows);
    }

    /** Plant a real, ordinary (non-system) global row under each guidance key —
     *  the coexistence trigger. The guidance keys are now in the reserved
     *  {@code system} namespace, so the ordinary service write this used to make
     *  ({@code remember(..., SourceChannel.CONSOLE)}) is refused by the
     *  reserved-namespace guard. The colliding row is therefore persisted
     *  DIRECTLY through the Panache repository — below the service seam and its
     *  guard, without weakening it — mirroring a curator row (console channel,
     *  unlocked) that no ordinary write path can actually produce any more. The
     *  overlay must then suppress its own entry for that key rather than double
     *  it. */
    private void plantCuratorRows() {
        Scope global = scopes.requireBySlug("global");
        for (String key : GUIDANCE_KEYS) {
            Memory row = new Memory();
            row.ownerSubject = SUBJECT;
            row.scope = global;
            row.type = MemoryType.CONVENTION;
            row.key = key;
            row.content = "curator content for " + key;
            row.source = SourceChannel.CONSOLE;
            memories.persist(row);
        }
    }

    private static void assertExactlyOnePerGuidanceKey(List<Memory> rows) {
        for (String key : GUIDANCE_KEYS) {
            long count = rows.stream().filter(m -> key.equals(m.key)).count();
            assertThat(count)
                .as("exactly one entry for guidance key %s (no overlay/row doubling)", key)
                .isEqualTo(1);
        }
    }
}
