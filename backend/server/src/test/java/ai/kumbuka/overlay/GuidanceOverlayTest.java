package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit gate for the built-in guidance overlay: resource parsing, the
 * synthetic identity, and the two merge predicates in isolation (no database).
 * The real-database behaviour through the repositories is covered by
 * {@code GuidanceOverlayReadPathTest} and {@code GuidanceCoexistenceTest}.
 */
class GuidanceOverlayTest {

    private static final String KEY_TYPES = "convention.how-to-kumbuka.types";
    private static final String KEY_WRITING = "convention.how-to-kumbuka.writing";
    private static final String KEY_READING = "convention.how-to-kumbuka.reading";

    private final GuidanceOverlay overlay = bundledOverlay();

    /** The overlay built from the bundled default document (no external file). */
    private static GuidanceOverlay bundledOverlay() {
        return new GuidanceOverlay(GuidanceLoader.load(null, GuidanceOverlay.BUNDLED_RESOURCE));
    }

    // ---------------------------------------------------------------------
    // Stage A — resource parsing + synthetic identity
    // ---------------------------------------------------------------------

    @Test
    void parsesThreeConventionEntriesWithFixedProvenance() {
        List<Memory> entries = overlay.entries();
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(m -> m.key)
            .containsExactly(KEY_TYPES, KEY_WRITING, KEY_READING);
        for (Memory m : entries) {
            assertThat(m.type).isEqualTo(MemoryType.CONVENTION);
            assertThat(m.scope.slug).isEqualTo("global");
            assertThat(m.scope.kind).isEqualTo(ScopeKind.GLOBAL);
            assertThat(m.ownerSubject).isEqualTo(SystemSubject.SENTINEL);
            assertThat(m.source).isEqualTo(SourceChannel.SYSTEM);
            assertThat(m.lock).isEqualTo(MemoryLock.SYSTEM);
            assertThat(m.isPrivate).isFalse();
            assertThat(m.content).isNotBlank();
        }
    }

    @Test
    void timestampsAreStableAndEqualAcrossEntries() {
        Instant expected = Instant.parse("2026-07-25T00:00:00Z");
        for (Memory m : overlay.entries()) {
            assertThat(m.createdAt).isEqualTo(expected);
            assertThat(m.updatedAt).isEqualTo(expected);
        }
    }

    @Test
    void syntheticIdsAreVersion5AndStableAcrossInstances() {
        for (Memory m : overlay.entries()) {
            assertThat(m.logicalId.version()).isEqualTo(5);
            assertThat(m.logicalId.variant()).isEqualTo(2); // RFC 4122
        }
        // A second parse of the same resource yields identical ids — the
        // identity is derived deterministically, not generated per instance.
        GuidanceOverlay other = bundledOverlay();
        assertThat(other.entries()).extracting(m -> m.logicalId)
            .containsExactlyElementsOf(overlay.entries().stream().map(m -> m.logicalId).toList());
    }

    @Test
    void syntheticIdDerivesFromTheRenameInvariantLogicalName() {
        // The id is the version-5 UUID of the logical name (key minus the
        // leading "convention." namespace), NOT the fully-qualified key — so a
        // later key rename keeps the id stable.
        Memory types = overlay.entries().get(0);
        assertThat(types.logicalId)
            .isEqualTo(GuidanceOverlay.uuidV5(namespace(), "how-to-kumbuka.types"))
            .isNotEqualTo(GuidanceOverlay.uuidV5(namespace(), KEY_TYPES));
    }

    @Test
    void byIdResolvesKnownAndRejectsUnknown() {
        Memory first = overlay.entries().get(0);
        assertThat(overlay.byId(first.logicalId)).contains(first);
        assertThat(overlay.byId(UUID.randomUUID())).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Stage B/C — recall merge predicate matrix (empty rows)
    // ---------------------------------------------------------------------

    @Test
    void recall_defaultUnscoped_showsAllThree() {
        assertThat(overlay.mergeIntoRecall(List.of(), null, null, null, false)).hasSize(3);
    }

    @Test
    void recall_scopeGlobal_showsAllThree() {
        assertThat(overlay.mergeIntoRecall(List.of(), "global", null, null, false)).hasSize(3);
    }

    @Test
    void recall_projectScopeWithoutIncludeGlobal_showsNone() {
        assertThat(overlay.mergeIntoRecall(List.of(), "team", null, null, false)).isEmpty();
    }

    @Test
    void recall_projectScopeWithIncludeGlobal_showsAllThree() {
        assertThat(overlay.mergeIntoRecall(List.of(), "team", null, null, true)).hasSize(3);
    }

    @Test
    void recall_typeDecision_excludes_typeConvention_includes() {
        assertThat(overlay.mergeIntoRecall(List.of(), null, MemoryType.DECISION, null, false)).isEmpty();
        assertThat(overlay.mergeIntoRecall(List.of(), null, MemoryType.CONVENTION, null, false)).hasSize(3);
    }

    @Test
    void recall_queryMatchIncludes_nonMatchExcludes() {
        String needle = overlay.entries().get(2).content.substring(0, 12);
        assertThat(overlay.mergeIntoRecall(List.of(), null, null, needle, false))
            .anyMatch(m -> m.key.equals(KEY_READING));
        assertThat(overlay.mergeIntoRecall(List.of(), null, null, "zzq-no-such-substring", false)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Stage B/C — shared merge predicate matrix
    // ---------------------------------------------------------------------

    @Test
    void shared_unscopedAndGlobal_showAll_projectAndDecision_showNone() {
        assertThat(overlay.mergeIntoShared(List.of(), null, null)).hasSize(3);
        assertThat(overlay.mergeIntoShared(List.of(), "global", null)).hasSize(3);
        assertThat(overlay.mergeIntoShared(List.of(), "team", null)).isEmpty();
        assertThat(overlay.mergeIntoShared(List.of(), null, MemoryType.DECISION)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Stage C — coexistence: a real global row wins its key
    // ---------------------------------------------------------------------

    @Test
    void coexistence_realRowSuppressesTheOverlayEntryForItsKey() {
        Memory real = realGlobalRow(KEY_TYPES, "a real, tenant-owned row under this key",
            Instant.parse("2026-07-24T00:00:00Z"));

        List<Memory> merged = overlay.mergeIntoRecall(List.of(real), null, null, null, false);

        // No doubling: exactly one entry under the shadowed key, and it is the
        // real row, not the overlay.
        assertThat(merged).filteredOn(m -> m.key.equals(KEY_TYPES)).containsExactly(real);
        // The other two overlay entries still surface → 1 real + 2 overlay.
        assertThat(merged).hasSize(3);
        assertThat(merged).extracting(m -> m.key)
            .contains(KEY_TYPES, KEY_WRITING, KEY_READING);
    }

    @Test
    void coexistence_returnsRowsUntouchedWhenEveryKeyIsAlreadyPresent() {
        Memory a = realGlobalRow(KEY_TYPES, "row a", Instant.parse("2026-07-24T00:00:00Z"));
        Memory b = realGlobalRow(KEY_WRITING, "row b", Instant.parse("2026-07-23T00:00:00Z"));
        Memory c = realGlobalRow(KEY_READING, "row c", Instant.parse("2026-07-22T00:00:00Z"));
        List<Memory> rows = List.of(a, b, c);

        // All three overlay keys shadowed → overlay fully suppressed → the exact
        // same list instance is handed back (null behaviour change).
        assertThat(overlay.mergeIntoRecall(rows, null, null, null, false)).isSameAs(rows);
        assertThat(overlay.mergeIntoShared(rows, "global", null)).isSameAs(rows);
    }

    // ---------------------------------------------------------------------
    // Stage C — ordering: merged list is newest-first, stable
    // ---------------------------------------------------------------------

    @Test
    void ordering_mergedListIsNewestFirst() {
        Memory newest = realGlobalRow("team.newest", "newest", Instant.parse("2026-12-01T00:00:00Z"));
        Memory oldest = realGlobalRow("team.oldest", "oldest", Instant.parse("2020-01-01T00:00:00Z"));

        List<Memory> merged = overlay.mergeIntoShared(List.of(newest, oldest), null, null);

        // newest real row first, overlay (2026-07-25) in the middle, oldest last.
        assertThat(merged).first().isEqualTo(newest);
        assertThat(merged).last().isEqualTo(oldest);
        assertThat(merged).extracting(m -> m.updatedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static UUID namespace() {
        return UUID.fromString("b1e6a9d4-3c2f-4a8e-9f17-2d6c8b0a5e34");
    }

    private static Memory realGlobalRow(String key, String content, Instant updatedAt) {
        Scope global = new Scope();
        global.slug = "global";
        global.kind = ScopeKind.GLOBAL;
        Memory m = new Memory();
        m.logicalId = UUID.randomUUID();
        m.scope = global;
        m.type = MemoryType.CONVENTION;
        m.key = key;
        m.content = content;
        m.ownerSubject = "11111111-1111-1111-1111-111111111111";
        m.source = SourceChannel.CONSOLE;
        m.lock = MemoryLock.NONE;
        m.createdAt = updatedAt;
        m.updatedAt = updatedAt;
        return m;
    }
}
