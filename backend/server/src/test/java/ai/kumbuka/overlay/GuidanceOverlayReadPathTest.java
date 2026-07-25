package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database gate for the recall merge. A fresh test tenant carries no
 * bundled guidance rows (the seeder runs only via an internal call), so the
 * built-in guidance surfaces directly through {@code recall} — this asserts it
 * appears for exactly the filter combinations the recall SQL would have
 * surfaced a real global convention row for, and for no others.
 *
 * <p>Real-DB {@code @QuarkusTest} named {@code *Test} + {@code @TestTransaction}
 * so it rolls back and never disturbs the shared test database.
 */
@QuarkusTest
class GuidanceOverlayReadPathTest {

    static final String SUBJECT = "55555555-5555-5555-5555-555555555555";

    private static final String KEY_TYPES = "convention.how-to-kumbuka.types";
    private static final String KEY_WRITING = "convention.how-to-kumbuka.writing";
    private static final String KEY_READING = "convention.how-to-kumbuka.reading";
    private static final List<String> GUIDANCE_KEYS = List.of(KEY_TYPES, KEY_WRITING, KEY_READING);

    @Inject MemoryRepository memories;

    @Test
    @TestTransaction
    void defaultUnscopedView_showsTheThreeGuidanceEntries() {
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, null, null, null, false)))
            .containsExactlyInAnyOrderElementsOf(GUIDANCE_KEYS);
    }

    @Test
    @TestTransaction
    void scopeGlobal_showsTheThreeGuidanceEntries() {
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, "global", null, null, false)))
            .containsExactlyInAnyOrderElementsOf(GUIDANCE_KEYS);
    }

    @Test
    @TestTransaction
    void projectScopeWithoutIncludeGlobal_hidesGuidance() {
        // NULL PROBE anchor: with the overlay filter changed to "always append"
        // this assertion goes red — the guidance would wrongly appear for a
        // project-scoped read that did not ask for global.
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, "team", null, null, false))).isEmpty();
    }

    @Test
    @TestTransaction
    void projectScopeWithIncludeGlobal_showsGuidance() {
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, "team", null, null, true)))
            .containsExactlyInAnyOrderElementsOf(GUIDANCE_KEYS);
    }

    @Test
    @TestTransaction
    void typeFilter_conventionIncludes_decisionExcludes() {
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, "global", MemoryType.CONVENTION, null, false)))
            .containsExactlyInAnyOrderElementsOf(GUIDANCE_KEYS);
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, "global", MemoryType.DECISION, null, false)))
            .isEmpty();
    }

    @Test
    @TestTransaction
    void queryFilter_matchIncludes_nonMatchExcludes() {
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, null, null, "mnemonics", false)))
            .containsExactlyInAnyOrderElementsOf(GUIDANCE_KEYS);
        assertThat(guidanceKeysIn(memories.recall(SUBJECT, null, null, "zzq-no-such-substring", false)))
            .isEmpty();
    }

    @Test
    @TestTransaction
    void loadContext_belowCap_dropsNeitherRealRowNorGuidance() {
        // A real (non-guidance) convention row coexists with the overlay under
        // the default per-type cap — the grouped digest keeps all of them.
        Memory real = memories.remember(SUBJECT, "global", MemoryType.CONVENTION,
            "team.real-convention", "an ordinary tenant convention", SourceChannel.MCP);

        List<Memory> conventions = memories.loadContext(SUBJECT, null, Set.of(MemoryType.CONVENTION))
            .get(MemoryType.CONVENTION);

        assertThat(conventions).extracting(m -> m.key)
            .contains(KEY_TYPES, KEY_WRITING, KEY_READING, "team.real-convention");
        assertThat(conventions).extracting(m -> m.logicalId).contains(real.logicalId);
    }

    private static List<String> guidanceKeysIn(List<Memory> rows) {
        return rows.stream()
            .map(m -> m.key)
            .filter(GUIDANCE_KEYS::contains)
            .toList();
    }
}
