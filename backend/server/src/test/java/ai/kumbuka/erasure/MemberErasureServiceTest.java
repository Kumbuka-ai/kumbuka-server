package ai.kumbuka.erasure;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-behaviour tests for {@link MemberErasureService} against the
 * DevServices Postgres container. The service runs the JPQL DELETE +
 * two UPDATEs that ADR-0015 specifies; this test seeds a scenario,
 * triggers it, and verifies what survives.
 *
 * Invariants the test enforces:
 *   • private entries owned by the erased member are deleted in full
 *   • shared entries authored by the member are kept; only ownerSubject
 *     is rewritten to the tombstone sentinel (content unchanged)
 *   • scopes created_by the erased member keep their content; only
 *     createdBy is rewritten to the tombstone
 *   • another member's private entries are NOT touched (cross-subject
 *     isolation — the destructive scope is "this subject within this tenant")
 *   • idempotency — a second erase of the same subject returns zeros
 *   • blank subject + sentinel subject are refused with IllegalArgumentException
 *     (defence-in-depth so a misrouted call can't mass-strip)
 */
@QuarkusTest
class MemberErasureServiceTest {

    @Inject MemberErasureService service;
    @Inject ErasureConfig config;

    private static final String ALICE = "alice-kc-sub";
    private static final String BOB   = "bob-kc-sub";

    /**
     * The V1 seed gives us a singleton private + global scope already.
     * The test additionally creates a 'project' scope created_by Alice
     * so the scope-tombstone branch has a row to act on.
     */
    @BeforeEach
    @Transactional
    void cleanAndSeed() {
        Memory.deleteAll();
        // Don't drop the V1 seed scopes — only the project one we add here.
        Scope.delete("kind = ?1", ScopeKind.PROJECT);

        Scope alphaProject = new Scope();
        alphaProject.slug = "alpha";
        alphaProject.name = "alpha";
        alphaProject.kind = ScopeKind.PROJECT;
        alphaProject.fixed = false;
        alphaProject.archived = false;
        alphaProject.createdBy = ALICE;
        alphaProject.persist();

        final Scope privateScope = Scope.find("kind = ?1", ScopeKind.PRIVATE).firstResult();
        final Scope globalScope  = Scope.find("kind = ?1", ScopeKind.GLOBAL).firstResult();
        assertThat(privateScope).as("V1 seed must include the private scope").isNotNull();
        assertThat(globalScope).as("V1 seed must include the global scope").isNotNull();

        persistMemory(ALICE, privateScope, "alice-private-1", MemoryType.DECISION, "alice secret 1");
        persistMemory(ALICE, privateScope, "alice-private-2", MemoryType.STATUS,   "alice secret 2");
        persistMemory(BOB,   privateScope, "bob-private",     MemoryType.DECISION, "bob secret");
        persistMemory(ALICE, globalScope,  "alice-global",    MemoryType.CONVENTION, "ship daily");
        persistMemory(ALICE, alphaProject, "alice-project",   MemoryType.CONSTRAINT, "no force pushes");
        persistMemory(BOB,   globalScope,  "bob-global",      MemoryType.GLOSSARY,   "RLS = Row-Level Security");
    }

    @Transactional
    void persistMemory(String owner, Scope scope, String key, MemoryType type, String content) {
        Memory m = new Memory();
        m.ownerSubject = owner;
        m.scope = scope;
        m.type = type;
        m.key = key;
        m.content = content;
        m.source = SourceChannel.CONSOLE;
        m.persist();
    }

    @Test
    @Transactional
    void erasesPrivateOnly_keepsAndTombstonesShared() {
        MemberErasureService.EraseResult out = service.eraseSubject(ALICE);

        assertThat(out.privatePurged())
            .as("Alice's two private rows must be deleted in full")
            .isEqualTo(2);
        assertThat(out.sharedTombstoned())
            .as("Alice's two shared rows (global + project) keep their content but lose authorship")
            .isEqualTo(2);
        assertThat(out.scopesTombstoned())
            .as("The project scope Alice created must have its created_by tombstoned")
            .isEqualTo(1);

        // Alice's private rows are gone
        assertThat(Memory.find("ownerSubject = ?1 and scope.kind = ?2",
                ALICE, ScopeKind.PRIVATE).count()).isZero();

        // Bob's private row is untouched
        assertThat(Memory.find("ownerSubject = ?1 and scope.kind = ?2",
                BOB, ScopeKind.PRIVATE).count())
            .as("cross-subject isolation: Bob's data must NEVER be touched")
            .isEqualTo(1);

        // Alice's shared rows are kept with content; only owner_subject flipped
        final String tombstone = config.tombstoneSubject();
        assertThat(Memory.<Memory>list("ownerSubject = ?1", ALICE))
            .as("no shared row should still reference Alice's KC sub")
            .isEmpty();
        assertThat(Memory.<Memory>list(
                "ownerSubject = ?1 and content = ?2", tombstone, "ship daily"))
            .hasSize(1);
        assertThat(Memory.<Memory>list(
                "ownerSubject = ?1 and content = ?2", tombstone, "no force pushes"))
            .hasSize(1);

        // Project scope content stays; only createdBy is tombstoned
        Scope alpha = Scope.find("slug = ?1", "alpha").firstResult();
        assertThat(alpha).isNotNull();
        assertThat(alpha.createdBy).isEqualTo(tombstone);
    }

    @Test
    @Transactional
    void isIdempotent() {
        service.eraseSubject(ALICE);
        MemberErasureService.EraseResult second = service.eraseSubject(ALICE);

        assertThat(second.privatePurged()).isZero();
        assertThat(second.sharedTombstoned()).isZero();
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
}
