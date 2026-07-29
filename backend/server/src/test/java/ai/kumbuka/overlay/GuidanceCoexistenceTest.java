package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
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
 * <p>Writes a real curator-authored row under each guidance key inside the
 * rolled-back transaction, then asserts exactly one entry per guidance key on
 * both read chokepoints — the curator row wins its key and the overlay entry
 * for that key is suppressed. The rule is defensive now that guidance is never
 * planted as rows, but a curator entry under a guidance key must still win over
 * the overlay rather than double it.
 */
@QuarkusTest
class GuidanceCoexistenceTest {

    static final String SUBJECT = "66666666-6666-6666-6666-666666666666";

    private static final List<String> GUIDANCE_KEYS = List.of(
        "convention.how-to-kumbuka.types",
        "convention.how-to-kumbuka.writing",
        "convention.how-to-kumbuka.reading");

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;

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

    /** Write a real curator-authored global row under each guidance key — the
     *  coexistence trigger. Guidance keys are not in the reserved namespace, so
     *  an ordinary console write is accepted; the overlay must then suppress its
     *  own entry for that key rather than double it. */
    private void plantCuratorRows() {
        for (String key : GUIDANCE_KEYS) {
            memories.remember(SUBJECT, "global", MemoryType.CONVENTION, key,
                "curator content for " + key, SourceChannel.CONSOLE);
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
