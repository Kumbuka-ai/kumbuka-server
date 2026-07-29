package ai.kumbuka.mcp;

import ai.kumbuka.mcp.dto.Dtos;
import ai.kumbuka.service.MemberWritePolicy;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mute, proved through the REAL persistence pipeline (vs. the
 * mocked-repo unit gate in {@link MemoryToolsMuteTest}). Real {@link MemoryTools}
 * + real repositories + a real {@code user_account.muted} row, against the
 * DevServices Postgres with RLS in force and the seeded {@code private}/{@code global}
 * scopes — the same path a live MCP tool call takes.
 *
 * <p>Asserts the full outcome, not just that the gate throws: a muted member's
 * shared write is rejected AND nothing is persisted; their private write
 * persists; reads are unaffected; an unmuted member writes normally.
 *
 * <p>Tagged {@code integration} so the {@code -Pintegration} failsafe profile
 * runs it as a regression gate.
 */
@QuarkusTest
@Tag("integration")
class MemberMuteIT {

    @Inject MemoryTools tools;
    @Inject MuteTestSupport mute;
    @InjectMock SecurityIdentity identity;

    private void callerIs(String subject) {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn(subject);
        when(identity.getPrincipal()).thenReturn(jwt);
    }

    private int recallCount(String scope, String marker) {
        Dtos.RecallResult r = tools.memory_recall(scope, null, marker, false);
        return r.count();
    }

    @Test
    void mutedMember_sharedRemember_isRejected_andNothingPersists() {
        callerIs("mute-it-shared");
        mute.setMuted("mute-it-shared", true);
        String marker = "MUTE-IT-shared-7f3c91";

        assertThatThrownBy(() -> tools.memory_remember(marker, "decision", "global", null, null))
            .isInstanceOf(MemberWritePolicy.MutedException.class);

        // The write never reached the table.
        assertThat(recallCount("global", marker)).isZero();
    }

    @Test
    void mutedMember_privateRemember_succeeds_andPersists() {
        callerIs("mute-it-private");
        mute.setMuted("mute-it-private", true);
        String marker = "MUTE-IT-private-a1b2c3";

        Dtos.RememberResult out = tools.memory_remember(marker, "decision", "private", null, null);
        assertThat(out.memory()).isNotNull();

        // The muted member's private scope is fully writable.
        assertThat(recallCount("private", marker)).isEqualTo(1);
    }

    @Test
    void mutedMember_canStillRead() {
        // Seed a shared global memory as an unmuted author, then a muted member
        // must still be able to recall it (reads are never gated).
        callerIs("mute-it-author");
        mute.setMuted("mute-it-author", false);
        String marker = "MUTE-IT-readable-d4e5f6";
        tools.memory_remember(marker, "decision", "global", null, null);

        callerIs("mute-it-reader");
        mute.setMuted("mute-it-reader", true);
        assertThat(recallCount("global", marker)).isEqualTo(1);
    }

    @Test
    void unmutedMember_sharedRemember_succeeds() {
        callerIs("mute-it-unmuted");
        mute.setMuted("mute-it-unmuted", false);
        String marker = "MUTE-IT-unmuted-09abef";

        Dtos.RememberResult out = tools.memory_remember(marker, "decision", "global", null, null);
        assertThat(out.memory()).isNotNull();
        assertThat(recallCount("global", marker)).isEqualTo(1);
    }
}
