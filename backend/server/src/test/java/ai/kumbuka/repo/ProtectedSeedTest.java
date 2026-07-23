package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import ai.kumbuka.seed.TenantSeedService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Release gate for D-CORE-11 — protected system-seed mnemonics.
 *
 * <p>The five contract points from the session brief:
 * <ol>
 *   <li>A protected row cannot be deleted by ANY app-layer path (member,
 *       admin, internal repository call) — the structural trigger fires.</li>
 *   <li>{@code protected} cannot be set or cleared via {@code memory_remember}
 *       at any role — the {@code @Tool} arg simply does not exist.</li>
 *   <li>The seeder is idempotent: run twice → three rows, not six;
 *       content unchanged.</li>
 *   <li>Unprotected entries delete exactly as before (no regression on
 *       the ordinary path).</li>
 *   <li>A pre-existing unprotected row with the same key as a seed entry
 *       is promoted to {@code protected=true} on the first seed run —
 *       this is the migration path for the live johannesbayer how-to
 *       entries that exist as ordinary conventions today.</li>
 * </ol>
 *
 * <p>The kumbuka-scope {@code open_question.write-confirmation-setting} part
 * of the brief lives in a different tenant + scope and is impossible to
 * exercise from this in-process test (the OSS server has only the
 * singleton tenant). The structural property covered here — the seeder
 * only ever writes by explicit key in the configured fixture — is what
 * keeps that entry safe.
 */
@QuarkusTest
class ProtectedSeedTest {

    static final String MEMBER = "33333333-3333-3333-3333-333333333333";
    static final String ADMIN  = "44444444-4444-4444-4444-444444444444";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject TenantSeedService seedService;
    @Inject ScopeRepository scopes;

    // ---------------------------------------------------------------------
    // Gate 3 — idempotent seeding
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void seeder_isIdempotent_runTwiceProducesTheSameRowCount() {
        // First run plants 3 rows (or promotes existing ones — same end state).
        seedService.seedCurrentTenant();
        List<Memory> firstRun = listSeeds();
        assertThat(firstRun).hasSize(3);
        for (Memory m : firstRun) {
            assertThat(m.lock).isEqualTo(MemoryLock.SYSTEM);
            assertThat(m.source).isEqualTo(SourceChannel.SYSTEM);
            assertThat(m.ownerSubject).isEqualTo(SystemSubject.SENTINEL);
        }

        // Second run is a no-op on row count.
        seedService.seedCurrentTenant();
        List<Memory> secondRun = listSeeds();
        assertThat(secondRun).hasSize(3);

        // Same ids — no duplicates, no churn.
        assertThat(secondRun).extracting(m -> m.logicalId)
            .containsExactlyInAnyOrderElementsOf(firstRun.stream().map(m -> m.logicalId).toList());
    }

    // ---------------------------------------------------------------------
    // Gate 5 — promote pre-existing unprotected row by key (the live
    // johannesbayer migration path)
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void seeder_promotesPreExistingUnprotectedRowByKey() {
        // Mimic the live johannesbayer state: the same key exists as a
        // regular convention authored by a human admin.
        String key = "convention.how-to-kumbuka.types";
        Memory pre = memories.remember(
            ADMIN, "global", MemoryType.CONVENTION, key,
            "an earlier hand-written version", SourceChannel.CONSOLE);
        assertThat(pre.lock).isEqualTo(MemoryLock.NONE);
        assertThat(pre.ownerSubject).isEqualTo(ADMIN);
        assertThat(pre.source).isEqualTo(SourceChannel.CONSOLE);

        seedService.seedCurrentTenant();

        // After the seed run: same row id (no duplicate), now protected +
        // owned by the system identity, content rewritten to the fixture.
        Memory after = Memory.findById(pre.logicalId);
        assertThat(after).isNotNull();
        assertThat(after.lock).isEqualTo(MemoryLock.SYSTEM);
        assertThat(after.ownerSubject).isEqualTo(SystemSubject.SENTINEL);
        // The first-write channel is immutable (updatable = false): the
        // promotion transfers ownership and the lock but keeps the original
        // creation provenance — this row was born through the console.
        assertThat(after.source).isEqualTo(SourceChannel.CONSOLE);
        // Content has been rewritten to the canonical fixture.
        assertThat(after.content).contains("Kumbuka mnemonics are typed");
    }

    // ---------------------------------------------------------------------
    // Gate 1 — structural delete-lock
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void protectedRow_cannotBeDeleted_byOrdinaryForget() {
        seedService.seedCurrentTenant();
        // memory_forget is the MCP delete path; ProtectedEntryException
        // (DELETE_BLOCKED) is the typed surface the @Tool wrapper renders
        // as a structured error.
        assertThatThrownBy(() ->
            memories.forget(SystemSubject.SENTINEL, "global", null,
                            "convention.how-to-kumbuka.types"))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.DELETE_BLOCKED);
    }

    @Test
    @TestTransaction
    void protectedRow_cannotBeUpdated_viaAdminPath() {
        seedService.seedCurrentTenant();
        // SharedMemoryRepository.update is the admin PATCH / console-editor path.
        // A protected row must be read-only there too (the DB trigger only
        // guards DELETE) — UPDATE_BLOCKED is the typed surface the mapper turns
        // into HTTP 409.
        Memory seed = listSeeds().get(0);
        assertThat(seed.lock).isEqualTo(MemoryLock.SYSTEM);
        assertThatThrownBy(() -> sharedMemories.update(seed.logicalId, "tampered content", null, ADMIN))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.UPDATE_BLOCKED);
    }

    @Test
    @TestTransaction
    void protectedRow_cannotBeDeleted_byNonOwnerMemberEither() {
        seedService.seedCurrentTenant();
        // V16 Delta 4 (ratified): shared forget-by-key is now author-independent
        // (matching the shared uniqueness + forget-by-id). A different subject
        // hitting the protected key therefore MATCHES the canonical head — and is
        // blocked at the structural DELETE trigger (a STRONGER guarantee than the
        // old owner-inclusive silent miss). The typed ProtectedEntryException is
        // the surface the @Tool wrapper renders as a structured error.
        // (No post-delete query: the P0001 trigger raise aborts the Postgres
        // transaction, so the typed exception IS the assertion — the BEFORE DELETE
        // block guarantees the row was never removed.)
        assertThatThrownBy(() ->
            memories.forget(MEMBER, "global", null, "convention.how-to-kumbuka.types"))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.DELETE_BLOCKED);
    }

    @Test
    @TestTransaction
    void protectedRow_contentUpdate_viaSystemReseed_succeeds() {
        // Amendment 2: there is NO UPDATE trigger — the only legitimate in-place
        // UPDATE of a locked row is the SYSTEM re-seed, which must NOT be blocked.
        // Re-seed the same key with CHANGED content through the SYSTEM path; it
        // succeeds (a blanket UPDATE trigger would have broken exactly this).
        seedService.seedCurrentTenant();
        String key = "convention.how-to-kumbuka.types";
        Memory updated = memories.remember(
            SystemSubject.SENTINEL, "global", MemoryType.CONVENTION, key,
            "re-seeded canonical content", SourceChannel.SYSTEM);

        assertThat(updated.lock).isEqualTo(MemoryLock.SYSTEM);
        assertThat(updated.content).isEqualTo("re-seeded canonical content");
        // Last-editor provenance stamped on the in-place SYSTEM edit (Amendment 4).
        assertThat(updated.updatedSource).isEqualTo(SourceChannel.SYSTEM);
    }

    // ---------------------------------------------------------------------
    // Gate 2 — protected cannot be set via MCP remember
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void member_cannotShadowProtectedKey_inAnotherKeyspace() {
        // Per-owner uniqueness on (tenant, scope, owner, key) would allow
        // a member to write the same key in their OWN keyspace, silently
        // creating a parallel row. D-CORE-11 rejects this up-front.
        seedService.seedCurrentTenant();
        assertThatThrownBy(() ->
            memories.remember(
                MEMBER, "global", MemoryType.CONVENTION,
                "convention.how-to-kumbuka.types",
                "spoof attempt — should never persist",
                SourceChannel.MCP))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.UPSERT_BLOCKED);
    }

    @Test
    @TestTransaction
    void admin_cannotShadowProtectedKey_viaConsoleEither() {
        seedService.seedCurrentTenant();
        assertThatThrownBy(() ->
            memories.remember(
                ADMIN, "global", MemoryType.CONVENTION,
                "convention.how-to-kumbuka.writing",
                "admin attempt — also blocked",
                SourceChannel.CONSOLE))
            .isInstanceOf(ProtectedEntryException.class);
    }

    // ---------------------------------------------------------------------
    // Gate 4 — ordinary entries delete as before (no regression)
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void unprotectedEntries_deleteExactlyAsBefore() {
        Memory m = memories.remember(
            MEMBER, "global", MemoryType.GLOSSARY,
            "regression.unprotected.delete",
            "ordinary entry, no protection", SourceChannel.MCP);
        assertThat(m.lock).isEqualTo(MemoryLock.NONE);

        int deleted = memories.forget(MEMBER, "global", null,
                                       "regression.unprotected.delete");
        // The delete IS the assertion — Panache returns the affected row
        // count; 1 confirms the structural trigger did NOT fire on this
        // unprotected row (no PSQL P0001 → no ProtectedEntryException).
        // We deliberately do NOT re-fetch via findById since the in-transaction
        // Hibernate L1 cache would still return the row even after a successful
        // delete-via-JPQL — flush+clear would test Hibernate's session
        // semantics, not the D-CORE-11 contract.
        assertThat(deleted).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Domain invariants on the pre-persist check
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void plainMember_cannotClaimTheSystemSentinelAsOwner() {
        // The pre-persist check in Memory rejects a non-SYSTEM row that
        // claims the system sentinel — the sentinel is the seeder's
        // identity, not a name anyone else can take.
        assertThatThrownBy(() -> {
            Memory m = new Memory();
            m.ownerSubject = SystemSubject.SENTINEL;
            m.scope = scopes.requireBySlug("private");
            m.type = MemoryType.GLOSSARY;
            m.content = "spoof — should never persist";
            m.source = SourceChannel.MCP;
            memories.persist(m);
        }).hasMessageContaining("reserved for SYSTEM writes");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private List<Memory> listSeeds() {
        return Memory.<Memory>find(
            "ownerSubject = ?1 and lock = ?2",
            SystemSubject.SENTINEL, MemoryLock.SYSTEM).list();
    }
}
