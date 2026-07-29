package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.ProtectedEntryException.Reason;
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
    @Inject ScopeRepository scopes;

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

    // ---------- the system channel is the one exempt writer ------------------

    @Test
    @TestTransaction
    void systemSeedToReservedKey_isAllowed() {
        Memory seeded = memories.remember(
            SENTINEL, "global", MemoryType.CONVENTION, "system.how-to.reading",
            "built-in guidance", SourceChannel.SYSTEM);

        assertThat(seeded.key).isEqualTo("system.how-to.reading");
        assertThat(seeded.source).isEqualTo(SourceChannel.SYSTEM);
        assertThat(seeded.lock).isEqualTo(MemoryLock.SYSTEM);
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
}
