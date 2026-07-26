package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.MemoryLock;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anti-hardcoding gate (Stage 3). Every assertion here runs against
 * {@code extensibility-fixture.json} — a DIFFERENT number of entries than the
 * bundled default (five, not three) with five DIFFERENT types, four of them not
 * {@code convention}. A suite that only ever sees three conventions proves
 * nothing about extensibility, so this suite deliberately never touches the
 * bundled default.
 *
 * <p>It pins the two properties that make the overlay genuinely extensible:
 * no code path assumes a fixed entry count, and the type filter is evaluated
 * against each entry's OWN type — not a hardcoded {@code convention}.
 */
class GuidanceExtensibilityTest {

    private static final String KEY_CONVENTION = "convention.example-alpha";
    private static final String KEY_GLOSSARY = "glossary.example-term";
    private static final String KEY_DECISION = "decision.example-choice";
    private static final String KEY_CONSTRAINT = "constraint.example-rule";
    private static final String KEY_STATUS = "status.example-phase";

    private final GuidanceOverlay overlay = fixtureOverlay();

    private static GuidanceOverlay fixtureOverlay() {
        try {
            Path fixture = Path.of(
                GuidanceExtensibilityTest.class.getResource("/guidance/extensibility-fixture.json").toURI());
            return new GuidanceOverlay(GuidanceLoader.load(fixture, GuidanceOverlay.BUNDLED_RESOURCE));
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void loadsAllFiveEntries_notAFixedThree() {
        assertThat(overlay.entries()).hasSize(5);
        assertThat(overlay.entries()).extracting(m -> m.key)
            .containsExactly(KEY_CONVENTION, KEY_GLOSSARY, KEY_DECISION, KEY_CONSTRAINT, KEY_STATUS);
        assertThat(overlay.entries()).extracting(m -> m.type).containsExactly(
            MemoryType.CONVENTION, MemoryType.GLOSSARY, MemoryType.DECISION,
            MemoryType.CONSTRAINT, MemoryType.STATUS);
    }

    @Test
    void recall_typeFilter_isEvaluatedPerEntryOwnType() {
        assertThat(recallKeys(MemoryType.CONVENTION)).containsExactly(KEY_CONVENTION);
        assertThat(recallKeys(MemoryType.GLOSSARY)).containsExactly(KEY_GLOSSARY);
        assertThat(recallKeys(MemoryType.DECISION)).containsExactly(KEY_DECISION);
        assertThat(recallKeys(MemoryType.CONSTRAINT)).containsExactly(KEY_CONSTRAINT);
        assertThat(recallKeys(MemoryType.STATUS)).containsExactly(KEY_STATUS);
        assertThat(recallKeys(null)).containsExactlyInAnyOrder(
            KEY_CONVENTION, KEY_GLOSSARY, KEY_DECISION, KEY_CONSTRAINT, KEY_STATUS);
    }

    @Test
    void recall_nonConventionEntry_notServedUnderConvention_andServedUnderOwnType() {
        // Both directions of the fixed defect:
        //  - a glossary entry is NOT wrongly served when convention is requested,
        assertThat(recallKeys(MemoryType.CONVENTION)).doesNotContain(KEY_GLOSSARY);
        //  - and it IS served when its own type is requested.
        assertThat(recallKeys(MemoryType.GLOSSARY)).contains(KEY_GLOSSARY);
    }

    @Test
    void shared_typeFilter_isEvaluatedPerEntryOwnType() {
        assertThat(sharedKeys(MemoryType.CONVENTION)).containsExactly(KEY_CONVENTION);
        assertThat(sharedKeys(MemoryType.GLOSSARY)).containsExactly(KEY_GLOSSARY);
        assertThat(sharedKeys(MemoryType.DECISION)).containsExactly(KEY_DECISION);
        assertThat(sharedKeys(null)).hasSize(5);
    }

    @Test
    void ordering_overlayEntriesSitBetweenNewerAndOlderRealRows() {
        Memory newer = realGlobalRow("team.newer", Instant.parse("2027-01-01T00:00:00Z"));
        Memory older = realGlobalRow("team.older", Instant.parse("2020-01-01T00:00:00Z"));

        List<Memory> merged = overlay.mergeIntoShared(List.of(newer, older), null, null);

        assertThat(merged).first().isEqualTo(newer);
        assertThat(merged).last().isEqualTo(older);
        assertThat(merged).hasSize(7); // 5 overlay + 2 real
        assertThat(merged).extracting(m -> m.updatedAt)
            .isSortedAccordingTo(Comparator.reverseOrder());
    }

    private List<String> recallKeys(MemoryType type) {
        return overlay.mergeIntoRecall(List.of(), "global", type, null, false)
            .stream().map(m -> m.key).toList();
    }

    private List<String> sharedKeys(MemoryType type) {
        return overlay.mergeIntoShared(List.of(), "global", type)
            .stream().map(m -> m.key).toList();
    }

    private static Memory realGlobalRow(String key, Instant updatedAt) {
        Scope global = new Scope();
        global.slug = "global";
        global.kind = ScopeKind.GLOBAL;
        Memory m = new Memory();
        m.logicalId = UUID.randomUUID();
        m.scope = global;
        m.type = MemoryType.CONVENTION;
        m.key = key;
        m.content = "real row " + key;
        m.ownerSubject = "11111111-1111-1111-1111-111111111111";
        m.source = SourceChannel.CONSOLE;
        m.lock = MemoryLock.NONE;
        m.createdAt = updatedAt;
        m.updatedAt = updatedAt;
        return m;
    }
}
