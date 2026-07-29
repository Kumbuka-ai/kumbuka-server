package ai.kumbuka.mcp;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code first_mcp_connected_at} write-once first-connect stamp, proved
 * through the REAL persistence pipeline: real {@link MemoryTools} + a real
 * {@code user_account} row against the DevServices Postgres with RLS in force.
 * The set-point lives in the {@code mcp} adapter (constraint.protocol-neutrality).
 *
 * <p>Asserts the contract: a fresh member starts null; the FIRST authenticated
 * MCP request stamps the instant; a SECOND request leaves it unchanged
 * (write-once). The write-once guard is itself the structural defence against
 * activity-monitoring (constraint.audit-no-activity-monitoring) — there is no
 * counter and no "last seen": the value is set exactly once and never moves.
 */
@QuarkusTest
@Tag("integration")
class FirstMcpConnectedAtIT {

    @Inject MemoryTools tools;
    @Inject FirstMcpConnectTestSupport support;
    @InjectMock SecurityIdentity identity;

    private void callerIs(String subject) {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn(subject);
        when(identity.getPrincipal()).thenReturn(jwt);
    }

    // ---- a provisioned member who never connects over MCP stays null ----
    @Test
    void freshMember_neverConnected_isNull() {
        support.seedMember("fc-it-never");
        assertThat(support.firstConnectedAt("fc-it-never")).isNull();
    }

    // ---- the FIRST authenticated MCP request stamps the instant ----
    @Test
    void firstAuthenticatedRequest_stampsTimestamp() {
        support.seedMember("fc-it-first");
        assertThat(support.firstConnectedAt("fc-it-first")).isNull();

        callerIs("fc-it-first");
        Instant before = Instant.now();
        tools.memory_scopes();   // any authenticated tool counts as a connection

        Instant stamped = support.firstConnectedAt("fc-it-first");
        assertThat(stamped).isNotNull();
        assertThat(stamped).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    // ---- a SECOND request leaves the stamp unchanged (write-once) ----
    @Test
    void secondRequest_leavesTimestampUnchanged() {
        support.seedMember("fc-it-once");
        callerIs("fc-it-once");

        tools.memory_scopes();
        Instant first = support.firstConnectedAt("fc-it-once");
        assertThat(first).isNotNull();

        // A burst of further authenticated calls (different tools) must NOT move
        // the stamp — write-once. If the field were an activity log this would
        // advance; it must not.
        tools.memory_scopes();
        tools.memory_recall(null, null, null, false);
        tools.memory_load_context(null, null);

        assertThat(support.firstConnectedAt("fc-it-once")).isEqualTo(first);
    }
}
