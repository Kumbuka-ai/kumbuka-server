package ai.kumbuka.mcp;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import ai.kumbuka.mcp.dto.Dtos;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.service.MemberWritePolicy;
import ai.kumbuka.service.WritePolicyResolver;
import ai.kumbuka.service.WritePolicyResolver.DefaultScopeStatus;
import ai.kumbuka.service.WritePolicyResolver.Resolved;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-CORE-2 mute gate on the MCP write tools. A muted member's SHARED writes
 * (remember/forget on a non-private scope) are rejected before the repository
 * is touched; their PRIVATE scope and an unmuted member are unaffected.
 *
 * <p>Repos are CDI-mocked (the gate must fire before any repo call); the
 * muted {@code user_account} row is seeded through {@link MuteTestSupport}, a
 * tenant-bound helper, into the same default test tenant the tools resolve.
 */
@QuarkusTest
class MemoryToolsMuteTest {

    @Inject MemoryTools tools;
    @Inject MuteTestSupport mute;
    @InjectMock SecurityIdentity identity;
    @InjectMock MemoryRepository memories;
    @InjectMock ScopeRepository scopes;
    @InjectMock WritePolicyResolver policyResolver;

    private void callerIs(String subject) {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn(subject);
        when(identity.getPrincipal()).thenReturn(jwt);
        // remember() calls resolve() before the gate; an explicit scope means
        // the policy is not the gate, but the call must not NPE.
        lenient().when(policyResolver.resolve()).thenReturn(
            new Resolved(WritePolicy.PROJECT, WritePolicy.PROJECT, DefaultScopeStatus.OK, "alpha"));
        // FEAT-19: the unmuted/private write paths now resolve the scope for the
        // scope-lock pre-check — default every slug to an open (unlocked) scope.
        // The muted-shared paths reject at the mute gate before this is reached.
        lenient().when(scopes.requireBySlug(anyString())).thenAnswer(i -> {
            Scope s = new Scope();
            s.id = UUID.randomUUID();
            String slug = i.getArgument(0);
            s.slug = slug;
            s.kind = "private".equals(slug) ? ScopeKind.PRIVATE : ScopeKind.PROJECT;
            s.locked = false;
            return s;
        });
    }

    private Memory persisted(String scopeSlug) {
        Scope s = new Scope();
        s.id = UUID.randomUUID();
        s.slug = scopeSlug;
        s.kind = "private".equals(scopeSlug) ? ScopeKind.PRIVATE : ScopeKind.PROJECT;
        Memory m = new Memory();
        m.logicalId = UUID.randomUUID();
        m.scope = s;
        m.type = MemoryType.DECISION;
        m.content = "x";
        m.ownerSubject = "s";
        m.source = SourceChannel.MCP;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }

    @Test
    void mutedMember_cannotRememberToSharedScope() {
        callerIs("muted-remember");
        mute.setMuted("muted-remember", true);

        assertThatThrownBy(() -> tools.memory_remember("x", "decision", "alpha", null, null))
            .isInstanceOf(MemberWritePolicy.MutedException.class);

        // The gate fired before any write reached the repository.
        verify(memories, never()).remember(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void mutedMember_canStillRememberToPrivateScope() {
        callerIs("muted-private");
        mute.setMuted("muted-private", true);
        when(memories.remember(eq("muted-private"), eq("private"), any(), any(), any(), eq(SourceChannel.MCP)))
            .thenReturn(persisted("private"));

        assertThatCode(() -> tools.memory_remember("x", "decision", "private", null, null))
            .doesNotThrowAnyException();
        verify(memories).remember(eq("muted-private"), eq("private"), any(), any(), any(), eq(SourceChannel.MCP));
    }

    @Test
    void unmutedMember_canRememberToSharedScope() {
        callerIs("not-muted");
        mute.setMuted("not-muted", false);
        when(memories.remember(eq("not-muted"), eq("alpha"), any(), any(), any(), eq(SourceChannel.MCP)))
            .thenReturn(persisted("alpha"));

        Dtos.RememberResult out = tools.memory_remember("x", "decision", "alpha", null, null);
        assertThat(out.memory()).isNotNull();
    }

    @Test
    void mutedMember_cannotForgetInSharedScope() {
        callerIs("muted-forget");
        mute.setMuted("muted-forget", true);

        assertThatThrownBy(() -> tools.memory_forget("alpha", null, "some-key"))
            .isInstanceOf(MemberWritePolicy.MutedException.class);
        verify(memories, never()).forget(any(), anyString(), any(), any());
    }

    @Test
    void mutedMember_canStillForgetInPrivateScope() {
        callerIs("muted-forget-priv");
        mute.setMuted("muted-forget-priv", true);
        when(memories.forget(eq("muted-forget-priv"), eq("private"), any(), eq("k"))).thenReturn(1);

        Dtos.ForgetResult out = tools.memory_forget("private", null, "k");
        assertThat(out.deleted()).isEqualTo(1);
    }
}
