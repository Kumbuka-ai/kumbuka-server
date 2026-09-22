package ai.kumbuka.erasure;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-behaviour tests for {@link MemberErasureService} against the
 * DevServices Postgres container. The service runs the one JPQL UPDATE the
 * core still owns out of ADR-0015; this test seeds a scenario, triggers it,
 * and verifies what survives.
 *
 * Invariants the test enforces:
 *   • scopes created_by the erased member keep their content; only
 *     createdBy is rewritten to the tombstone
 *   • another member's scope provenance is NOT touched (cross-subject
 *     isolation — the destructive scope is "this subject within this tenant")
 *   • idempotency — a second erase of the same subject returns zero
 *   • blank subject + sentinel subject are refused with IllegalArgumentException
 *     (defence-in-depth so a misrouted call can't mass-strip)
 *   • the result carries the scope tombstone ALONE — the content counts left
 *     with the memory engine and are absent rather than reported as zero
 */
@QuarkusTest
class MemberErasureServiceTest {

    @Inject MemberErasureService service;
    @Inject ErasureConfig config;

    private static final String ALICE = "erasure-alice-kc-sub";
    private static final String BOB   = "erasure-bob-kc-sub";

    /**
     * Slugs chosen so they cannot collide with other tests' fixtures
     * (notably {@code WritePolicyResolverTest} which uses {@code alpha}).
     * The DevServices Postgres is shared across the test run, so test
     * scopes must be self-quarantined.
     */
    private static final String ALICE_SLUG = "erasure-test-project";
    private static final String BOB_SLUG   = "erasure-test-project-bob";

    /**
     * Two self-quarantined 'project' scopes, one created_by Alice and one
     * created_by Bob, so the tombstone branch has a row to act on and the
     * cross-subject isolation has a witness.
     */
    @BeforeEach
    @Transactional
    void cleanAndSeed() {
        // Remove our own fixtures only, never other tests' scopes.
        Scope.delete("slug in ?1", java.util.List.of(ALICE_SLUG, BOB_SLUG));

        persistScope(ALICE_SLUG, ALICE);
        persistScope(BOB_SLUG, BOB);
    }

    /** Remove our own fixtures so we don't leak state to downstream tests. */
    @AfterEach
    @Transactional
    void cleanup() {
        Scope.delete("slug in ?1", java.util.List.of(ALICE_SLUG, BOB_SLUG));
    }

    private void persistScope(String slug, String createdBy) {
        Scope s = new Scope();
        s.slug = slug;
        s.name = slug;
        s.kind = ScopeKind.PROJECT;
        s.fixed = false;
        s.archived = false;
        s.createdBy = createdBy;
        s.persist();
    }

    @Test
    @Transactional
    void tombstonesScopeProvenance_ofTheErasedSubjectOnly() {
        MemberErasureService.EraseResult out = service.eraseSubject(ALICE);

        assertThat(out.scopesTombstoned())
            .as("The project scope Alice created must have its created_by tombstoned")
            .isEqualTo(1);

        final String tombstone = config.tombstoneSubject();

        Scope aliceScope = Scope.find("slug = ?1", ALICE_SLUG).firstResult();
        assertThat(aliceScope).isNotNull();
        assertThat(aliceScope.createdBy)
            .as("the scope survives; only its authorship metadata is severed")
            .isEqualTo(tombstone);
        assertThat(aliceScope.name)
            .as("scope content is untouched — this is anonymisation, not deletion")
            .isEqualTo(ALICE_SLUG);

        Scope bobScope = Scope.find("slug = ?1", BOB_SLUG).firstResult();
        assertThat(bobScope).isNotNull();
        assertThat(bobScope.createdBy)
            .as("cross-subject isolation: Bob's provenance must NEVER be touched")
            .isEqualTo(BOB);
    }

    @Test
    @Transactional
    void isIdempotent() {
        service.eraseSubject(ALICE);
        MemberErasureService.EraseResult second = service.eraseSubject(ALICE);

        assertThat(second.scopesTombstoned()).isZero();
    }

    @Test
    @Transactional
    void rejectsBlankSubject() {
        assertThatThrownBy(() -> service.eraseSubject(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subject required");
        assertThatThrownBy(() -> service.eraseSubject(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.eraseSubject("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void refusesTombstoneSentinelAsSubject() {
        // Misrouted erase against the sentinel would mass-strip every
        // formerly-erased member's authorship if it ran. We refuse it.
        assertThatThrownBy(() -> service.eraseSubject(config.tombstoneSubject()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tombstone");
    }

    /**
     * The content half of ADR-0015 — deleting private entries and tombstoning
     * shared authorship — left the core with the memory engine. The result
     * shape must say so by omission: a {@code privatePurged} field reporting
     * {@code 0} would assert that the member had nothing to erase, which this
     * service can no longer know. Absence is the only true statement available
     * to it, and it stays true only if something holds it in place.
     */
    @Test
    void resultReportsTheScopeTombstoneAloneAndNeverAZeroContentCount() {
        assertThat(Arrays.stream(MemberErasureService.EraseResult.class.getRecordComponents())
                .map(RecordComponent::getName))
            .containsExactly("scopesTombstoned");
    }
}
