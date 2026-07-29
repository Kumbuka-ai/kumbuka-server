package ai.kumbuka.mcp;

import ai.kumbuka.mcp.dto.Dtos;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * scope-content-lock enforcement on the MCP wire, proved
 * through the REAL persistence pipeline (real {@link MemoryTools} + real
 * repositories against DevServices Postgres with RLS in force) — the same path
 * a live MCP tool call takes. Mirrors the {@link MemberMuteIT} integration-layer
 * pattern; the trust boundary (DB/RLS) is never mocked.
 *
 * <p>The MCP tools carry no {@code @RolesAllowed}, so a {@code @InjectMock}
 * identity drives subject + role here. The console (admin REST) surface — whose
 * methods ARE role-gated and so cannot be reached by a direct call with a
 * mocked identity — is covered end-to-end in {@link ai.kumbuka.admin.ScopeLockRestIT}.
 *
 * <p>Scope slugs are unique per concern so the shared single test tenant does
 * not let one case contaminate another; the one case that touches the shared
 * {@code global} scope restores its lock state in a finally.
 */
@QuarkusTest
@Tag("integration")
class ScopeLockEnforcementIT {

    @Inject MemoryTools tools;
    @Inject ScopeLockTestSupport support;
    @InjectMock SecurityIdentity identity;

    @AfterEach
    void cleanup() {
        support.cleanup();
    }

    private void callerIs(String subject, String... roles) {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn(subject);
        when(identity.getPrincipal()).thenReturn(jwt);
        when(identity.getRoles()).thenReturn(new HashSet<>(List.of(roles)));
    }

    // ---- 1. member MCP remember on a locked scope → typed error, no row ----
    @Test
    void member_mcpRemember_lockedScope_blocked_noRow() {
        callerIs("sl-m1", "member");
        support.ensureProject("sl-locked-1", true);

        Dtos.RememberResult r = tools.memory_remember("SL-IT-r1", "decision", "sl-locked-1", null, null);

        assertThat(r.memory()).isNull();
        assertThat(r.error()).isNotNull();
        assertThat(r.error().code()).isEqualTo("SCOPE_READ_ONLY");
        assertThat(support.entryCount("sl-locked-1")).isZero();
    }

    // ---- 2. member MCP forget on a locked scope → blocked, row intact ------
    @Test
    void member_mcpForget_lockedScope_blocked_rowIntact() {
        callerIs("sl-m2", "member");
        support.seedEntryThenLock("sl-locked-2", "k-forget", "keep me");

        Dtos.ForgetResult fr = tools.memory_forget("sl-locked-2", null, "k-forget");

        assertThat(fr.deleted()).isZero();
        assertThat(fr.error()).isNotNull();
        assertThat(fr.error().code()).isEqualTo("SCOPE_READ_ONLY");
        assertThat(support.entryCount("sl-locked-2")).isEqualTo(1);
    }

    // ---- 4. admin MCP write on a locked scope → STILL blocked (the sharp one) ----
    @Test
    void admin_mcpRemember_lockedScope_stillBlocked() {
        callerIs("sl-a1", "admin");
        support.ensureProject("sl-locked-4", true);

        Dtos.RememberResult r = tools.memory_remember("SL-IT-admin-mcp", "decision", "sl-locked-4", null, null);

        assertThat(r.memory()).isNull();
        assertThat(r.error()).isNotNull();
        assertThat(r.error().code()).isEqualTo("SCOPE_READ_ONLY");
        assertThat(support.entryCount("sl-locked-4")).isZero();
    }

    // ---- 7. fixed scope (global) is lockable (orthogonality); then member write blocked ----
    @Test
    void fixedGlobalScope_isLockable_thenMemberWriteBlocked() {
        callerIs("sl-m7", "member");
        try {
            support.setLocked("global", true);
            assertThat(support.isLocked("global")).isTrue();

            Dtos.RememberResult r = tools.memory_remember("SL-IT-global", "decision", "global", null, null);
            assertThat(r.error()).isNotNull();
            assertThat(r.error().code()).isEqualTo("SCOPE_READ_ONLY");
        } finally {
            support.setLocked("global", false);   // restore: global is shared across tests
        }
        assertThat(support.isLocked("global")).isFalse();
    }

    // ---- 8. copy-out: member reads a locked entry, creates into an OPEN scope → ok ----
    @Test
    void copyOut_memberReadsLocked_createsIntoOpen_succeeds() {
        callerIs("sl-m8", "member");
        support.seedEntryThenLock("sl-locked-8", "k-copy", "copyable content");
        support.ensureProject("sl-open-8", false);

        // read is unaffected on a locked scope
        Dtos.RecallResult recall = tools.memory_recall("sl-locked-8", null, "copyable", false);
        assertThat(recall.count()).isEqualTo(1);

        // copy-out == ordinary create into a non-locked target; no special path
        Dtos.RememberResult out = tools.memory_remember("copyable content", "decision", "sl-open-8", "k-copy-out", null);
        assertThat(out.memory()).isNotNull();
        assertThat(out.error()).isNull();
        assertThat(support.entryCount("sl-open-8")).isEqualTo(1);
    }

    // ---- 10. P1: the feature never touches private — a private write still works ----
    @Test
    void p1_privateWrite_unaffectedByScopeLock() {
        callerIs("sl-m10", "member");
        Dtos.RememberResult r = tools.memory_remember("SL-IT-private", "decision", "private", "p-key", null);
        assertThat(r.memory()).isNotNull();
        assertThat(r.error()).isNull();
    }
}
