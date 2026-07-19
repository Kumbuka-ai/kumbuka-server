package ai.kumbuka.mcp;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.service.WritePolicyResolver;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end wiring of the write-rate limiter on the MCP tool surface:
 * the {@code @RateLimitedWrite} interceptor runs in the real CDI weave of
 * {@code memory_remember} (before the transactional and tenant-binding
 * interceptors), throttles past the configured burst with a clean tool
 * error carrying a retry hint, and leaves the read tools untouched.
 *
 * <p>Runs under a tiny default band (burst 3, refill 1/60s) so the series
 * is exact: three writes pass, the fourth is rejected — repeated for the
 * measurement series without wall-clock refill interference.
 */
@QuarkusTest
@Tag("integration")
@TestProfile(McpWriteRateLimitIT.TinyBandProfile.class)
class McpWriteRateLimitIT {

    public static class TinyBandProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "kumbuka.rate-limit.default-burst-capacity", "3",
                "kumbuka.rate-limit.default-refill-tokens", "1",
                "kumbuka.rate-limit.default-refill-period-seconds", "60");
        }
    }

    @Inject MemoryTools tools;
    @InjectMock SecurityIdentity identity;
    @InjectMock MemoryRepository memories;
    @InjectMock ScopeRepository scopes;
    @InjectMock WritePolicyResolver policyResolver;

    private static final String SUBJECT = "mcp-flow-subject-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn(SUBJECT);
        when(identity.getPrincipal()).thenReturn(jwt);
        when(identity.isAnonymous()).thenReturn(false);
        lenient().when(scopes.requireBySlug(anyString()))
            .thenAnswer(i -> scope(i.getArgument(0)));
        lenient().when(memories.remember(anyString(), anyString(), any(), any(), anyString(), any()))
            .thenAnswer(i -> memory(scope(i.getArgument(1)), i.getArgument(4)));
    }

    @Test
    void writesPassWithinBurstThenThrottleWithRetryHint() {
        for (int i = 0; i < 3; i++) {
            assertThatCode(() ->
                tools.memory_remember("within band", "decision", "global", null, null))
                .as("write within the burst capacity")
                .doesNotThrowAnyException();
        }

        assertThatThrownBy(() ->
            tools.memory_remember("past band", "decision", "global", null, null))
            .isInstanceOf(ToolCallException.class)
            .hasMessageContaining("write rate limit exceeded")
            .hasMessageContaining("retry in");
    }

    @Test
    void readToolsAreNeverThrottled() {
        // Different principal so this test never interacts with the write
        // test's bucket.
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn("mcp-reader-subject");
        when(identity.getPrincipal()).thenReturn(jwt);
        when(memories.recall(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(java.util.List.of());

        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> tools.memory_recall(null, null, null, false))
                .as("reads are outside the write-rate limiter's scope")
                .doesNotThrowAnyException();
        }
    }

    private static Scope scope(String slug) {
        Scope s = new Scope();
        s.id = UUID.randomUUID();
        s.slug = slug;
        s.name = slug;
        s.kind = ScopeKind.GLOBAL;
        s.fixed = true;
        s.archived = false;
        s.createdAt = Instant.now();
        return s;
    }

    private static Memory memory(Scope sc, String content) {
        Memory m = new Memory();
        m.logicalId = UUID.randomUUID();
        m.scope = sc;
        m.type = MemoryType.DECISION;
        m.content = content;
        m.ownerSubject = SUBJECT;
        m.source = SourceChannel.MCP;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }
}
