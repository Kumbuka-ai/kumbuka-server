package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
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
            assertThat(m.protected_).isTrue();
            assertThat(m.source).isEqualTo(SourceChannel.SYSTEM);
            assertThat(m.ownerSubject).isEqualTo(SystemSubject.SENTINEL);
        }

        // Second run is a no-op on row count.
        seedService.seedCurrentTenant();
        List<Memory> secondRun = listSeeds();
        assertThat(secondRun).hasSize(3);

        // Same ids — no duplicates, no churn.
        assertThat(secondRun).extracting(m -> m.id)
            .containsExactlyInAnyOrderElementsOf(firstRun.stream().map(m -> m.id).toList());
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
        assertThat(pre.protected_).isFalse();
        assertThat(pre.ownerSubject).isEqualTo(ADMIN);
        assertThat(pre.source).isEqualTo(SourceChannel.CONSOLE);

        seedService.seedCurrentTenant();

        // After the seed run: same row id (no duplicate), now protected +
        // owned by the system identity, content rewritten to the fixture.
        Memory after = Memory.findById(pre.id);
        assertThat(after).isNotNull();
        assertThat(after.protected_).isTrue();
        assertThat(after.ownerSubject).isEqualTo(SystemSubject.SENTINEL);
        assertThat(after.source).isEqualTo(SourceChannel.SYSTEM);
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
        assertThat(seed.protected_).isTrue();
        assertThatThrownBy(() -> sharedMemories.update(seed.id, "tampered content", null))
            .isInstanceOf(ProtectedEntryException.class)
            .extracting(e -> ((ProtectedEntryException) e).reason())
            .isEqualTo(ProtectedEntryException.Reason.UPDATE_BLOCKED);
    }

    @Test
    @TestTransaction
    void protectedRow_cannotBeDeleted_byNonOwnerMemberEither() {
        seedService.seedCurrentTenant();
        // Even a different subject hitting the same key gets blocked at
        // the structural trigger — the per-owner key check in forget()
        // makes this a 0-row delete in the unprotected case, but the
        // trigger still fires if a row IS matched (it's not in this case,
        // hence no exception — the structural block protects when a
        // matching row WOULD be deleted, not when the predicate misses).
        int deleted = memories.forget(MEMBER, "global", null,
                                       "convention.how-to-kumbuka.types");
        // Per-owner predicate misses (no row owned by MEMBER with this
        // key) → 0 deleted, no exception. The protected row stays.
        assertThat(deleted).isZero();
        assertThat(listSeeds()).hasSize(3);
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
        assertThat(m.protected_).isFalse();

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
            "ownerSubject = ?1 and protected_ = true",
            SystemSubject.SENTINEL).list();
    }
}
