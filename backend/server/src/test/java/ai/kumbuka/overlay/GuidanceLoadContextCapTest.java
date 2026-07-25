package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.kumbuka.repo.MemoryRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary gate for the per-type digest cap. The overlay entries are merged
 * into {@code recall} before {@code loadContext} applies its per-type cap, so
 * they must count toward that cap in the same newest-first order as real rows —
 * neither a real row nor an overlay entry may be silently dropped at the edge.
 *
 * <p>Runs with the per-type limit lowered to two so the cap bites against the
 * three guidance entries alone.
 */
@QuarkusTest
@TestProfile(GuidanceLoadContextCapTest.SmallCapProfile.class)
class GuidanceLoadContextCapTest {

    static final String SUBJECT = "77777777-7777-7777-7777-777777777777";

    @Inject MemoryRepository memories;

    @Test
    @TestTransaction
    void loadContext_capsTheMergedList_andCountsOverlayEntriesInOrder() {
        List<Memory> full = memories.recall(SUBJECT, null, MemoryType.CONVENTION, null, false);
        // The overlay is present in the merged recall (at least the 3 entries).
        assertThat(full.size()).isGreaterThanOrEqualTo(3);

        List<Memory> capped = memories.loadContext(SUBJECT, null, Set.of(MemoryType.CONVENTION))
            .get(MemoryType.CONVENTION);

        // The cap counts the overlay entries: the digest is exactly the top-N of
        // the merged, newest-first list — not the full list, and not the list
        // with the overlay bypassing the cap.
        assertThat(capped).hasSize(2);
        assertThat(capped).extracting(m -> m.logicalId)
            .containsExactlyElementsOf(full.subList(0, 2).stream().map(m -> m.logicalId).toList());
    }

    public static class SmallCapProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.load-context.per-type-limit", "2");
        }
    }
}
