package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The release gate for ADR-0003. Private memories must be invisible to
 * every other user (members, admins) via every code path (MCP repository,
 * Shared/admin repository).
 *
 * If a future change breaks this test, treat it as a security incident:
 * stop, revert, and reason about why the data-access layer let go of the
 * invariant.
 */
@QuarkusTest
class PrivateIsolationTest {

    static final String SUBJECT_A = "11111111-1111-1111-1111-111111111111";
    static final String SUBJECT_B = "22222222-2222-2222-2222-222222222222";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject ScopeRepository scopes;

    // ---------------------------------------------------------------------
    // MCP code path — MemoryRepository
    // ---------------------------------------------------------------------

    @Test
    @Transactional
    void privateMemory_isNotVisibleToOtherUsers_viaMcpRecall() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "auth", "use OAuth", SourceChannel.MCP);

        List<Memory> bRecall = memories.recall(SUBJECT_B, "private", null, null, false);
        assertThat(bRecall).noneMatch(x -> x.id.equals(m.id));

        List<Memory> bRecallAll = memories.recall(SUBJECT_B, null, null, null, false);
        assertThat(bRecallAll).noneMatch(x -> x.id.equals(m.id));
    }

    @Test
    @Transactional
    void privateMemory_isNotDeletableByOtherUsers_viaMcpForget() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "auth", "use OAuth", SourceChannel.MCP);

        int byId = memories.forget(SUBJECT_B, "private", m.id, null);
        assertThat(byId).isZero();

        int byKey = memories.forget(SUBJECT_B, "private", null, "auth");
        assertThat(byKey).isZero();

        // The row still belongs to A.
        List<Memory> aRecall = memories.recall(SUBJECT_A, "private", null, null, false);
        assertThat(aRecall).anyMatch(x -> x.id.equals(m.id));
    }

    @Test
    @Transactional
    void privateMemory_isOwnerWriteOnly_upsertDoesNotCollideAcrossUsers() {
        Memory a = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "k", "A's content", SourceChannel.MCP);
        Memory b = memories.remember(SUBJECT_B, "private", MemoryType.DECISION, "k", "B's content", SourceChannel.MCP);

        assertThat(a.id).isNotEqualTo(b.id);
        assertThat(memories.recall(SUBJECT_A, "private", null, null, false))
            .anyMatch(x -> x.id.equals(a.id))
            .noneMatch(x -> x.id.equals(b.id));
        assertThat(memories.recall(SUBJECT_B, "private", null, null, false))
            .anyMatch(x -> x.id.equals(b.id))
            .noneMatch(x -> x.id.equals(a.id));
    }

    // ---------------------------------------------------------------------
    // Admin code path — SharedMemoryRepository
    // ---------------------------------------------------------------------

    @Test
    @Transactional
    void privateMemory_isNotVisibleViaAdminListShared() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        List<Memory> allShared = sharedMemories.listShared(null, null);
        assertThat(allShared).noneMatch(x -> x.id.equals(m.id));
    }

    @Test
    @Transactional
    void privateMemory_isNotLookupableByIdViaAdmin() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        Memory looked = sharedMemories.findSharedById(m.id);
        assertThat(looked).isNull();
    }

    @Test
    @Transactional
    void privateMemory_isNotDeletableViaAdmin() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        int deleted = sharedMemories.deleteShared(m.id);
        assertThat(deleted).isZero();

        // Still owned by A.
        List<Memory> aRecall = memories.recall(SUBJECT_A, "private", null, null, false);
        assertThat(aRecall).anyMatch(x -> x.id.equals(m.id));
    }

    @Test
    @Transactional
    void privateMemory_updateViaAdminThrowsNotFound() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        assertThatThrownBy(() -> sharedMemories.update(m.id, "rewritten", null))
            .isInstanceOf(SharedMemoryRepository.MemoryNotFoundException.class);
    }

    @Test
    void adminCannotEvenAddressThePrivateScopeByName() {
        assertThatThrownBy(() -> sharedMemories.requireSharedScope("private"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
    }

    // ---------------------------------------------------------------------
    // Positive control — shared scopes work as expected.
    // ---------------------------------------------------------------------

    @Test
    @Transactional
    void globalScope_isVisibleToAllUsers() {
        Memory m = memories.remember(SUBJECT_A, "global", MemoryType.DECISION, null, "team-wide thing", SourceChannel.MCP);

        List<Memory> bRecall = memories.recall(SUBJECT_B, "global", null, null, false);
        assertThat(bRecall).anyMatch(x -> x.id.equals(m.id));

        List<Memory> adminView = sharedMemories.listShared("global", null);
        assertThat(adminView).anyMatch(x -> x.id.equals(m.id));
    }

    // ---------------------------------------------------------------------
    // D-CORE-5 — an unscoped read defaults to private + global only; project
    // memories surface only when the caller asks for that project explicitly.
    // ---------------------------------------------------------------------

    private String ensureProject(String slug) {
        if (scopes.findBySlug(slug).isEmpty()) {
            scopes.createProject(slug, slug, null, SUBJECT_A);
        }
        return slug;
    }

    @Test
    @Transactional
    void unscopedRecall_includesPrivateAndGlobal_excludesProject() {
        String proj = ensureProject("dcore5-recall");
        Memory priv = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, null, "my private", SourceChannel.MCP);
        Memory glob = memories.remember(SUBJECT_A, "global", MemoryType.DECISION, null, "team global", SourceChannel.MCP);
        Memory inProj = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, null, "project note", SourceChannel.MCP);

        List<Memory> unscoped = memories.recall(SUBJECT_A, null, null, null, false);
        assertThat(unscoped)
            .anyMatch(x -> x.id.equals(priv.id))
            .anyMatch(x -> x.id.equals(glob.id))
            .noneMatch(x -> x.id.equals(inProj.id));
    }

    @Test
    @Transactional
    void explicitProjectScope_returnsThatProjectsMemories() {
        String proj = ensureProject("dcore5-explicit");
        Memory inProj = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, null, "explicit project", SourceChannel.MCP);

        List<Memory> scoped = memories.recall(SUBJECT_A, proj, null, null, false);
        assertThat(scoped).anyMatch(x -> x.id.equals(inProj.id));
    }

    @Test
    @Transactional
    void unscopedLoadContext_excludesProject_includesGlobal() {
        String proj = ensureProject("dcore5-ctx");
        Memory glob = memories.remember(SUBJECT_A, "global", MemoryType.CONVENTION, null, "ctx global", SourceChannel.MCP);
        Memory inProj = memories.remember(SUBJECT_A, proj, MemoryType.CONVENTION, null, "ctx project", SourceChannel.MCP);

        List<Memory> digest = memories.loadContext(SUBJECT_A, null).values().stream()
            .flatMap(List::stream).toList();
        assertThat(digest)
            .anyMatch(x -> x.id.equals(glob.id))
            .noneMatch(x -> x.id.equals(inProj.id));
    }

    @Test
    @Transactional
    void explicitProjectWithIncludeGlobal_stillAddsGlobal() {
        String proj = ensureProject("dcore5-incl");
        Memory glob = memories.remember(SUBJECT_A, "global", MemoryType.DECISION, null, "incl global", SourceChannel.MCP);
        Memory inProj = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, null, "incl project", SourceChannel.MCP);

        List<Memory> scoped = memories.recall(SUBJECT_A, proj, null, null, true);
        assertThat(scoped)
            .anyMatch(x -> x.id.equals(inProj.id))
            .anyMatch(x -> x.id.equals(glob.id));
    }
}
