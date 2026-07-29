package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
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
        assertThat(bRecall).noneMatch(x -> x.logicalId.equals(m.logicalId));

        List<Memory> bRecallAll = memories.recall(SUBJECT_B, null, null, null, false);
        assertThat(bRecallAll).noneMatch(x -> x.logicalId.equals(m.logicalId));
    }

    @Test
    @Transactional
    void privateMemory_isNotDeletableByOtherUsers_viaMcpForget() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "auth", "use OAuth", SourceChannel.MCP);

        int byId = memories.forget(SUBJECT_B, "private", m.logicalId, null);
        assertThat(byId).isZero();

        int byKey = memories.forget(SUBJECT_B, "private", null, "auth");
        assertThat(byKey).isZero();

        // The row still belongs to A.
        List<Memory> aRecall = memories.recall(SUBJECT_A, "private", null, null, false);
        assertThat(aRecall).anyMatch(x -> x.logicalId.equals(m.logicalId));
    }

    @Test
    @Transactional
    void privateMemory_isOwnerWriteOnly_upsertDoesNotCollideAcrossUsers() {
        Memory a = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "k", "A's content", SourceChannel.MCP);
        Memory b = memories.remember(SUBJECT_B, "private", MemoryType.DECISION, "k", "B's content", SourceChannel.MCP);

        assertThat(a.logicalId).isNotEqualTo(b.logicalId);
        assertThat(memories.recall(SUBJECT_A, "private", null, null, false))
            .anyMatch(x -> x.logicalId.equals(a.logicalId))
            .noneMatch(x -> x.logicalId.equals(b.logicalId));
        assertThat(memories.recall(SUBJECT_B, "private", null, null, false))
            .anyMatch(x -> x.logicalId.equals(b.logicalId))
            .noneMatch(x -> x.logicalId.equals(a.logicalId));
    }

    // ---------------------------------------------------------------------
    // Admin code path — SharedMemoryRepository
    // ---------------------------------------------------------------------

    @Test
    @Transactional
    void privateMemory_isNotVisibleViaAdminListShared() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        List<Memory> allShared = sharedMemories.listShared(null, null);
        assertThat(allShared).noneMatch(x -> x.logicalId.equals(m.logicalId));
    }

    @Test
    @Transactional
    void privateMemory_isNotLookupableByIdViaAdmin() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        Memory looked = sharedMemories.findSharedById(m.logicalId);
        assertThat(looked).isNull();
    }

    @Test
    @Transactional
    void privateMemory_isNotDeletableViaAdmin() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        int deleted = sharedMemories.deleteShared(m.logicalId);
        assertThat(deleted).isZero();

        // Still owned by A.
        List<Memory> aRecall = memories.recall(SUBJECT_A, "private", null, null, false);
        assertThat(aRecall).anyMatch(x -> x.logicalId.equals(m.logicalId));
    }

    @Test
    @Transactional
    void privateMemory_updateViaAdminThrowsNotFound() {
        Memory m = memories.remember(SUBJECT_A, "private", MemoryType.DECISION, "x", "secret", SourceChannel.MCP);

        assertThatThrownBy(() -> sharedMemories.update(m.logicalId, "rewritten", null, SUBJECT_A))
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
        assertThat(bRecall).anyMatch(x -> x.logicalId.equals(m.logicalId));

        List<Memory> adminView = sharedMemories.listShared("global", null);
        assertThat(adminView).anyMatch(x -> x.logicalId.equals(m.logicalId));
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
            .anyMatch(x -> x.logicalId.equals(priv.logicalId))
            .anyMatch(x -> x.logicalId.equals(glob.logicalId))
            .noneMatch(x -> x.logicalId.equals(inProj.logicalId));
    }

    @Test
    @Transactional
    void explicitProjectScope_returnsThatProjectsMemories() {
        String proj = ensureProject("dcore5-explicit");
        Memory inProj = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, null, "explicit project", SourceChannel.MCP);

        List<Memory> scoped = memories.recall(SUBJECT_A, proj, null, null, false);
        assertThat(scoped).anyMatch(x -> x.logicalId.equals(inProj.logicalId));
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
            .anyMatch(x -> x.logicalId.equals(glob.logicalId))
            .noneMatch(x -> x.logicalId.equals(inProj.logicalId));
    }

    @Test
    @Transactional
    void defaultLoadContext_excludesOpenQuestion_explicitTypesIncludesIt() {
        // D-CORE-6: the default digest returns steering types only; open_question is
        // digested only when explicitly requested via the types set.
        Memory dec = memories.remember(SUBJECT_A, "global", MemoryType.DECISION, null, "d6 decision", SourceChannel.MCP);
        Memory oq = memories.remember(SUBJECT_A, "global", MemoryType.OPEN_QUESTION, null, "d6 open?", SourceChannel.MCP);

        List<Memory> byDefault = memories.loadContext(SUBJECT_A, null).values().stream()
            .flatMap(List::stream).toList();
        assertThat(byDefault)
            .anyMatch(x -> x.logicalId.equals(dec.logicalId))
            .noneMatch(x -> x.logicalId.equals(oq.logicalId));

        List<Memory> explicit = memories.loadContext(SUBJECT_A, null,
                java.util.EnumSet.of(MemoryType.OPEN_QUESTION)).values().stream()
            .flatMap(List::stream).toList();
        assertThat(explicit).anyMatch(x -> x.logicalId.equals(oq.logicalId));
    }

    @Test
    @Transactional
    void reference_persistsAndSurfacesInRecall() {
        // D-CORE-7: the reference column round-trips and is returned by recall
        // (the explicit lookup path); the digest projection omits it (unit-tested).
        Memory m = memories.remember(SUBJECT_A, "global", MemoryType.DECISION, null, "d7 content", SourceChannel.MCP);
        m.reference = "https://example.com/origin";

        List<Memory> recalled = memories.recall(SUBJECT_A, "global", null, "d7 content", false);
        assertThat(recalled).anyMatch(x -> "https://example.com/origin".equals(x.reference));
    }

    // ---------------------------------------------------------------------
    // D-CORE-5.1 — an unscoped read WITH a query becomes a discovery search
    // across private, global, and every PROJECT scope the caller sees by RLS.
    // WITHOUT a query it stays the D-CORE-5 default of private and global only.
    // RLS and the untouched private predicate remain the hard boundary.
    // ---------------------------------------------------------------------

    @Test
    @Transactional
    void unscopedRecall_queryGatesProjectVisibility_andHitCarriesScopeSlug() {
        String proj = ensureProject("dcore51-discovery");
        Memory inProj = memories.remember(
            SUBJECT_A, proj, MemoryType.DECISION, null, "dcore51 needle alpha", SourceChannel.MCP);

        // WITH a query → the project row is discoverable without naming its scope,
        // and the returned row carries its scope slug so the caller can pin it.
        List<Memory> withQuery = memories.recall(SUBJECT_A, null, null, "needle alpha", false);
        assertThat(withQuery).anyMatch(x -> x.logicalId.equals(inProj.logicalId));
        assertThat(withQuery)
            .filteredOn(x -> x.logicalId.equals(inProj.logicalId))
            .allMatch(x -> proj.equals(x.scope.slug));

        // WITHOUT a query the same project row is NOT in the default unscoped
        // view — D-CORE-5 still holds and the widening is query-gated only.
        List<Memory> noQuery = memories.recall(SUBJECT_A, null, null, null, false);
        assertThat(noQuery).noneMatch(x -> x.logicalId.equals(inProj.logicalId));
    }

    @Test
    @Transactional
    void unscopedRecall_withQuery_includesOwnPrivateButNeverAnotherUsersPrivate() {
        // Both users write a private row whose content matches the query. The
        // discovery search must return the CALLER's own private hit and never the
        // other user's private row — the private predicate is untouched.
        Memory aPriv = memories.remember(
            SUBJECT_A, "private", MemoryType.DECISION, null, "dcore51 secret marker", SourceChannel.MCP);
        Memory bPriv = memories.remember(
            SUBJECT_B, "private", MemoryType.DECISION, null, "dcore51 secret marker", SourceChannel.MCP);

        List<Memory> aHits = memories.recall(SUBJECT_A, null, null, "secret marker", false);
        assertThat(aHits)
            .anyMatch(x -> x.logicalId.equals(aPriv.logicalId))
            .noneMatch(x -> x.logicalId.equals(bPriv.logicalId));
    }

    @Test
    @Transactional
    void unscopedLoadContext_withMatchingProjectRow_stillExcludesProject() {
        // Regression: loadContext recalls with a null query, so the D-CORE-5.1
        // query-gated widening must never reach it — project rows stay out of the
        // digest even when their content would match a hypothetical query.
        String proj = ensureProject("dcore51-ctx");
        Memory inProj = memories.remember(
            SUBJECT_A, proj, MemoryType.CONVENTION, null, "dcore51 ctx needle", SourceChannel.MCP);

        List<Memory> digest = memories.loadContext(SUBJECT_A, null).values().stream()
            .flatMap(List::stream).toList();
        assertThat(digest).noneMatch(x -> x.logicalId.equals(inProj.logicalId));
    }

    @Test
    @Transactional
    void explicitProjectWithIncludeGlobal_stillAddsGlobal() {
        String proj = ensureProject("dcore5-incl");
        Memory glob = memories.remember(SUBJECT_A, "global", MemoryType.DECISION, null, "incl global", SourceChannel.MCP);
        Memory inProj = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, null, "incl project", SourceChannel.MCP);

        List<Memory> scoped = memories.recall(SUBJECT_A, proj, null, null, true);
        assertThat(scoped)
            .anyMatch(x -> x.logicalId.equals(inProj.logicalId))
            .anyMatch(x -> x.logicalId.equals(glob.logicalId));
    }

    // dogfood-16/19: the fixed (global) scope cannot be archived / un-archived /
    // renamed — the repo guards throw the typed ScopeFixedException (→ 409).
    @Test
    @Transactional
    void archive_fixedGlobalScope_throwsScopeFixed() {
        assertThatThrownBy(() -> scopes.archive("global"))
            .isInstanceOf(ScopeRepository.ScopeFixedException.class);
    }

    @Test
    @Transactional
    void unarchive_fixedGlobalScope_throwsScopeFixed() {
        assertThatThrownBy(() -> scopes.unarchive("global"))
            .isInstanceOf(ScopeRepository.ScopeFixedException.class);
    }

    @Test
    @Transactional
    void rename_fixedGlobalScope_throwsScopeFixed() {
        assertThatThrownBy(() -> scopes.rename("global", "Renamed", null))
            .isInstanceOf(ScopeRepository.ScopeFixedException.class);
    }

    @Test
    @Transactional
    void createProject_duplicateSlug_throwsScopeAlreadyExists() {
        ensureProject("dogfood19-dup");
        assertThatThrownBy(() -> scopes.createProject("dogfood19-dup", "Dup", null, SUBJECT_A))
            .isInstanceOf(ScopeRepository.ScopeAlreadyExistsException.class);
    }

    // ---- D-CORE-16: console create never silently overwrites (dogfood-21) ----

    @Test
    @Transactional
    void createShared_existingKeySameAuthor_throwsKeyExists_priorRowIntact() {
        String proj = ensureProject("dcore16-create");
        Memory first = memories.createShared(SUBJECT_A, proj, MemoryType.DECISION, "k.one", "first", SourceChannel.CONSOLE);
        assertThatThrownBy(() ->
            memories.createShared(SUBJECT_A, proj, MemoryType.DECISION, "k.one", "second", SourceChannel.CONSOLE))
            .isInstanceOf(MemoryRepository.KeyExistsException.class);
        assertThat(memories.recall(SUBJECT_A, proj, null, null, false))
            .filteredOn(x -> x.logicalId.equals(first.logicalId))
            .allMatch(x -> x.content.equals("first"));   // NOT overwritten
    }

    @Test
    @Transactional
    void createShared_existingKeyDifferentAuthor_throwsKeyExists_authorIndependent() {
        String proj = ensureProject("dcore16-author");
        memories.createShared(SUBJECT_A, proj, MemoryType.DECISION, "k.shared", "A", SourceChannel.CONSOLE);
        assertThatThrownBy(() ->
            memories.createShared(SUBJECT_B, proj, MemoryType.DECISION, "k.shared", "B", SourceChannel.CONSOLE))
            .isInstanceOf(MemoryRepository.KeyExistsException.class);
    }

    @Test
    @Transactional
    void rememberMcpPath_existingKey_stillUpserts_unchanged() {
        // Regression lock: the MCP path keeps upsert-by-key (D-CORE-16 leaves it).
        String proj = ensureProject("dcore16-mcp");
        Memory r1 = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, "k.up", "v1", SourceChannel.MCP);
        Memory r2 = memories.remember(SUBJECT_A, proj, MemoryType.DECISION, "k.up", "v2", SourceChannel.MCP);
        assertThat(r2.logicalId).isEqualTo(r1.logicalId);
        assertThat(r2.content).isEqualTo("v2");
    }

    @Test
    @Transactional
    void createShared_keylessEntries_doNotCollide() {
        String proj = ensureProject("dcore16-keyless");
        Memory a = memories.createShared(SUBJECT_A, proj, MemoryType.STATUS, null, "one", SourceChannel.CONSOLE);
        Memory b = memories.createShared(SUBJECT_A, proj, MemoryType.STATUS, null, "two", SourceChannel.CONSOLE);
        assertThat(a.logicalId).isNotEqualTo(b.logicalId);
    }

    // ---- D-CORE-17: scope-remap (lossless; private excluded; collision-guarded) --

    @Test
    @Transactional
    void remap_movesEntryToTargetScope_lossless() {
        String src = ensureProject("dcore17-src");
        String dst = ensureProject("dcore17-dst");
        Memory m = memories.createShared(SUBJECT_A, src, MemoryType.DECISION, "k.move", "content", SourceChannel.CONSOLE);
        memories.remap(m, dst, null);
        assertThat(m.scope.slug).isEqualTo(dst);
        assertThat(m.content).isEqualTo("content");
        assertThat(m.key).isEqualTo("k.move");
        assertThat(memories.recall(SUBJECT_A, src, null, null, false)).noneMatch(x -> x.logicalId.equals(m.logicalId));
        assertThat(memories.recall(SUBJECT_A, dst, null, null, false)).anyMatch(x -> x.logicalId.equals(m.logicalId));
    }

    @Test
    @Transactional
    void remap_toPrivate_isRejected() {
        String src = ensureProject("dcore17-priv");
        Memory m = memories.createShared(SUBJECT_A, src, MemoryType.DECISION, null, "x", SourceChannel.CONSOLE);
        assertThatThrownBy(() -> memories.remap(m, "private", null))
            .isInstanceOf(MemoryRepository.RemapPrivateForbiddenException.class);
    }

    @Test
    @Transactional
    void remap_keyCollisionInTarget_throwsKeyExists() {
        String src = ensureProject("dcore17-coll-src");
        String dst = ensureProject("dcore17-coll-dst");
        memories.createShared(SUBJECT_A, dst, MemoryType.DECISION, "k.dup", "incumbent", SourceChannel.CONSOLE);
        Memory m = memories.createShared(SUBJECT_A, src, MemoryType.DECISION, "k.dup", "mover", SourceChannel.CONSOLE);
        assertThatThrownBy(() -> memories.remap(m, dst, null))
            .isInstanceOf(MemoryRepository.KeyExistsException.class);
    }

    @Test
    @Transactional
    void remap_keyCollisionInTarget_withRenameOverride_succeeds() {
        String src = ensureProject("dcore17-rn-src");
        String dst = ensureProject("dcore17-rn-dst");
        memories.createShared(SUBJECT_A, dst, MemoryType.DECISION, "k.dup", "incumbent", SourceChannel.CONSOLE);
        Memory m = memories.createShared(SUBJECT_A, src, MemoryType.DECISION, "k.dup", "mover", SourceChannel.CONSOLE);
        memories.remap(m, dst, "k.dup-2");
        assertThat(m.scope.slug).isEqualTo(dst);
        assertThat(m.key).isEqualTo("k.dup-2");
    }

    @Test
    @Transactional
    void remap_protectedEntry_isRejected() {
        // D-CORE-11: a protected (locked) entry is not remappable. Plant it BELOW
        // the write seam (direct persist) — the system channel no longer persists
        // through the repository (it fails loud there); onCreate still requires
        // source=SYSTEM + the sentinel owner to carry a system lock.
        String src = ensureProject("dcore17-prot");
        Memory m = new Memory();
        m.ownerSubject = ai.kumbuka.domain.SystemSubject.SENTINEL;
        m.scope = scopes.requireBySlug(src);
        m.type = MemoryType.CONVENTION;
        m.key = "k.prot";
        m.content = "seed";
        m.source = SourceChannel.SYSTEM;
        m.lock = MemoryLock.SYSTEM;
        memories.persist(m);
        assertThat(m.lock).isEqualTo(MemoryLock.SYSTEM);
        assertThatThrownBy(() -> memories.remap(m, "global", null))
            .isInstanceOf(ai.kumbuka.repo.ProtectedEntryException.class);
    }

    // dogfood-16: archive is a reversible soft-hide — un-archive restores the row.
    @Test
    @Transactional
    void scope_archive_thenUnarchive_roundTrips() {
        String proj = ensureProject("dogfood16-restore");
        scopes.archive(proj);
        assertThat(scopes.requireBySlug(proj).archived).isTrue();
        // gone from the shared listing while archived
        assertThat(scopes.listShared()).noneMatch(s -> s.slug.equals(proj));

        scopes.unarchive(proj);
        assertThat(scopes.requireBySlug(proj).archived).isFalse();
        // visible in the shared listing again
        assertThat(scopes.listShared()).anyMatch(s -> s.slug.equals(proj));
    }

    // -----------------------------------------------------------------------
    // V16 (ADR-0024 §A1.3 (1)): scope-kind-differentiated keyed upsert. The
    // highest-risk correctness change — a shared keyed write by a SECOND author
    // must update the ONE canonical live head, not insert a parallel row that
    // the shared partial unique index then hard-rejects.
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void sharedKeyedUpsert_isAuthorIndependent_singleCanonicalHead() {
        String proj = ensureProject("v16-shared-upsert");
        Memory a = memories.remember(SUBJECT_A, proj, MemoryType.DECISION,
            "shared.k", "A writes", SourceChannel.MCP);
        // A different author upserts the SAME shared key — author-independent:
        // updates the canonical head in place, no parallel row, no collision.
        Memory b = memories.remember(SUBJECT_B, proj, MemoryType.DECISION,
            "shared.k", "B edits", SourceChannel.MCP);

        assertThat(b.logicalId).isEqualTo(a.logicalId);            // same canonical head
        assertThat(b.content).isEqualTo("B edits");
        assertThat(memories.recall(SUBJECT_A, proj, null, null, false))
            .filteredOn(x -> "shared.k".equals(x.key))
            .hasSize(1);                             // exactly one row for the key
    }

    @Test
    @Transactional
    void privateKeyedUpsert_isPerAuthor_distinctHeads() {
        // Private keeps a per-author keyspace (the owner-inclusive private
        // index) — A's and B's private `p.k` are distinct entries, invisible
        // to each other (the ADR-0003 guarantee, preserved by V16).
        Memory a = memories.remember(SUBJECT_A, "private", MemoryType.DECISION,
            "p.k", "A private", SourceChannel.MCP);
        Memory b = memories.remember(SUBJECT_B, "private", MemoryType.DECISION,
            "p.k", "B private", SourceChannel.MCP);

        assertThat(b.logicalId).isNotEqualTo(a.logicalId);         // distinct per-owner heads
        assertThat(memories.recall(SUBJECT_A, "private", null, null, false))
            .filteredOn(x -> "p.k".equals(x.key)).hasSize(1);
    }
}
