package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import ai.kumbuka.overlay.GuidanceOverlay;
import ai.kumbuka.repo.ProtectedEntryException.Reason;
import ai.kumbuka.util.SystemKeyNamespace;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reserved {@code system} key-namespace guard, exercised against a running
 * database on every shared write path the repository seam covers. A non-system
 * caller (MCP or console) cannot write a reserved key; the system channel — the
 * one writer allowed into the namespace — can.
 *
 * <p>This row-independent guard is the sole key reservation: it holds whether or
 * not a row currently exists under the key. NULL probe: removing the
 * {@code assertKeyNamespaceAllowed} call from the write methods turns the
 * rejection assertions below RED (the writes then succeed).
 * {@code @TestTransaction} rolls each method back, leaving no committed rows.
 */
@QuarkusTest
class SystemNamespaceGuardTest {

    static final String MEMBER = "33333333-3333-3333-3333-333333333333";
    static final String SENTINEL = "__system__";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject ScopeRepository scopes;
    @Inject GuidanceOverlay guidance;

    private String ensureProject(String slug) {
        if (scopes.findBySlug(slug).isEmpty()) {
            scopes.createProject(slug, slug, null, MEMBER);
        }
        return slug;
    }

    // ---------- non-system callers are rejected ------------------------------

    @Test
    @TestTransaction
    void mcpWriteToReservedDotKey_isRejected() {
        assertThatThrownBy(() -> memories.remember(
                MEMBER, "global", MemoryType.CONVENTION, "system.foo", "spoof", SourceChannel.MCP))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    @Test
    @TestTransaction
    void mcpWriteToBareSystemKey_isRejected() {
        assertThatThrownBy(() -> memories.remember(
                MEMBER, "global", MemoryType.CONVENTION, "system", "spoof", SourceChannel.MCP))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    @Test
    @TestTransaction
    void consoleCreateToReservedKey_isRejected() {
        assertThatThrownBy(() -> memories.createShared(
                MEMBER, "global", MemoryType.CONVENTION, "system.bar", "spoof", SourceChannel.CONSOLE))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    @Test
    @TestTransaction
    void remapIntoReservedKey_isRejected() {
        String src = ensureProject("nsguard-remap");
        Memory m = memories.createShared(
            MEMBER, src, MemoryType.DECISION, "ns.normal", "content", SourceChannel.CONSOLE);
        assertThatThrownBy(() -> memories.remap(m, "global", "system.moved"))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    @Test
    @TestTransaction
    void ordinaryWriteToBuiltInGuidanceKey_isRejected() {
        // The property this rename delivers: the built-in guidance keys now live
        // in the reserved namespace, so no ordinary caller can plant a real row
        // under one and shadow the built-in entry via suppress-by-key. Both
        // caller-facing channels are refused. Red probe: this suite's shared NULL
        // probe (drop assertKeyNamespaceAllowed) makes both writes succeed.
        assertThatThrownBy(() -> memories.remember(
                MEMBER, "global", MemoryType.CONVENTION,
                "system.how-to-kumbuka.types", "spoof", SourceChannel.MCP))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));

        assertThatThrownBy(() -> memories.createShared(
                MEMBER, "global", MemoryType.CONVENTION,
                "system.how-to-kumbuka.writing", "spoof", SourceChannel.CONSOLE))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    // ---------- the system channel can no longer persist ---------------------

    @Test
    @TestTransaction
    void systemChannelWrite_failsLoud() {
        // The seeder is gone and the guidance overlay never persists, so no
        // caller-facing writer emits SourceChannel.SYSTEM. Reaching a persisting
        // write with it is now a programming error: it dies loudly at the write
        // seam rather than silently stamping a system lock (the former re-seed
        // behaviour). Red probe: neutralise MemoryRepository.assertNotSystemChannel
        // and this write succeeds instead of throwing.
        assertThatThrownBy(() -> memories.remember(
                SENTINEL, "global", MemoryType.CONVENTION, "system.how-to.reading",
                "built-in guidance", SourceChannel.SYSTEM))
            .isInstanceOf(IllegalStateException.class);
    }

    // ---------- positive controls: non-reserved keys pass --------------------

    @Test
    @TestTransaction
    void nonReservedKey_mcpWrite_isAllowed() {
        // 'systematic' merely starts with the letters; the legacy guidance
        // keyspace 'convention.*' is likewise unaffected.
        assertThatCode(() -> {
            memories.remember(MEMBER, "global", MemoryType.CONVENTION,
                "systematic.review", "fine", SourceChannel.MCP);
            memories.remember(MEMBER, "global", MemoryType.CONVENTION,
                "convention.how-to-kumbuka.writing", "fine", SourceChannel.MCP);
        }).doesNotThrowAnyException();
    }

    @Test
    @TestTransaction
    void keylessWrite_isAllowed() {
        assertThatCode(() -> memories.remember(
                MEMBER, "global", MemoryType.STATUS, null, "keyless", SourceChannel.MCP))
            .doesNotThrowAnyException();
    }

    // ---------- delete side: the reserved namespace is refused ----------------
    //
    // The write side already refuses a reserved key on every channel; the delete
    // side must say the same, or a memory_forget on a reserved key returns
    // deleted:0 — indistinguishable from a key that never existed — and the
    // reservation is never learnable from the delete surface. All three delete
    // address ways are covered, row-independent. Red probe: neutralise
    // ReservedNamespaceGuard.assertDeleteAllowed and the four rejection tests
    // below go RED while the positive + teardown controls stay green (that is the
    // proof the probe hits the guard, not the suite).

    /** One built-in guidance entry, with the synthetic id an assistant sees in
     *  every recall result. Its key is in the reserved namespace by construction. */
    private Memory anOverlayEntry() {
        Memory e = guidance.entries().get(0);
        assertThat(SystemKeyNamespace.isReserved(e.key))
            .as("the built-in guidance keys live in the reserved namespace").isTrue();
        return e;
    }

    /** Plant a reserved-key, system-locked row BELOW the write seam (direct
     *  persist) — the only way such a row can exist, since every caller-facing
     *  write is refused and the system channel no longer persists. */
    private Memory plantReservedRow(String key, String content) {
        Memory m = new Memory();
        m.ownerSubject = SystemSubject.SENTINEL;
        m.scope = scopes.requireBySlug("global");
        m.type = MemoryType.CONVENTION;
        m.key = key;
        m.content = content;
        m.source = SourceChannel.SYSTEM;
        m.lock = MemoryLock.SYSTEM;
        memories.persist(m);
        return m;
    }

    // 1a — forget by a reserved key. A real reserved-key row can only exist below
    // the seam; plant one so we also prove the rejected delete leaves it untouched.
    @Test
    @TestTransaction
    void forget_byReservedKey_isRejected_rowUntouched() {
        plantReservedRow("system.planted.forget", "built-in");
        assertThatThrownBy(() ->
                memories.forget(MEMBER, "global", null, "system.planted.forget"))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
        assertThat(memories.count("key = ?1", "system.planted.forget"))
            .as("the guard refused before any delete ran — the row is untouched").isEqualTo(1);
    }

    // 1b — forget by the synthetic overlay id (the likelier delete address: it is
    // in every recall result, while the key would have to be typed by hand).
    @Test
    @TestTransaction
    void forget_bySyntheticOverlayId_isRejected() {
        Memory overlay = anOverlayEntry();
        assertThatThrownBy(() ->
                memories.forget(MEMBER, "global", overlay.logicalId, null))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    // 1c — deleteShared (the console single-delete) by the synthetic overlay id.
    // This path has no source parameter, so the guard is unconditional.
    @Test
    @TestTransaction
    void deleteShared_bySyntheticOverlayId_isRejected() {
        Memory overlay = anOverlayEntry();
        assertThatThrownBy(() -> sharedMemories.deleteShared(overlay.logicalId))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    // The headline row-independent case: a reserved key with neither a row nor a
    // built-in entry. Without the guard it would return deleted:0, a plain
    // absence; the guard is what makes the reservation learnable from a delete.
    @Test
    @TestTransaction
    void forget_reservedKey_noRowNoOverlayEntry_isRejected() {
        assertThatThrownBy(() ->
                memories.forget(MEMBER, "global", null, "system.gibt-es-nicht"))
            .isInstanceOfSatisfying(ProtectedEntryException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.RESERVED_NAMESPACE));
    }

    // Positive control: an ordinary key still deletes, and a missing ordinary key
    // stays an empty result with no error — the absence stays an absence where it
    // belongs.
    @Test
    @TestTransaction
    void forget_ordinaryKey_stillDeletes_missingOrdinaryKey_returnsZero() {
        memories.remember(MEMBER, "global", MemoryType.CONVENTION,
            "ordinary.forget.key", "content", SourceChannel.MCP);
        assertThat(memories.forget(MEMBER, "global", null, "ordinary.forget.key"))
            .as("an ordinary key still deletes").isEqualTo(1);
        assertThat(memories.forget(MEMBER, "global", null, "ordinary.never-existed"))
            .as("a missing ordinary key stays 0, no error").isZero();
    }

    // Teardown/erasure control (ADR-0024 Amendment 5): the guard sits on the
    // caller-facing seams, never on the teardown/erasure path. TenantDataPurge and
    // MemberErasure delete with Memory.delete/deleteAll — the bulk primitive
    // exercised here — which does not funnel through the guard, so a reserved-key
    // row is removed like any other in a purge or erasure.
    @Test
    @TestTransaction
    void teardownPrimitive_deletesReservedRow_guardNotOnThatPath() {
        plantReservedRow("system.planted.teardown", "built-in");
        assertThat(memories.count("key = ?1", "system.planted.teardown")).isEqualTo(1);

        long removed = Memory.delete("key = ?1", "system.planted.teardown");

        assertThat(removed)
            .as("the teardown/erasure bulk-delete primitive removes the reserved row")
            .isEqualTo(1);
    }
}
