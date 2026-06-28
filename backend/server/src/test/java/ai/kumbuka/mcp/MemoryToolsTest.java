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
import ai.kumbuka.service.WritePolicyResolver;
import ai.kumbuka.service.WritePolicyResolver.DefaultScopeStatus;
import ai.kumbuka.service.WritePolicyResolver.Resolved;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the five MCP tool methods on {@link MemoryTools}. Quarkus container
 * brings in the real {@code @Transactional}/{@code @TenantBound} weave so the
 * coverage agent sees the same bytecode the production path executes. The
 * SPI-side dependencies ({@link MemoryRepository}, {@link ScopeRepository},
 * {@link WritePolicyResolver}, the OIDC identity) are CDI-mocked.
 *
 * Focus: the write-policy resolution branches (D3 + handoff §F-2) — private
 * is never silently picked, an ASK policy returns a structured prompt rather
 * than guessing; plus shape assertions on the recall / forget / scopes /
 * load_context output that the MCP client depends on.
 */
@QuarkusTest
class MemoryToolsTest {

    @Inject MemoryTools tools;
    @InjectMock SecurityIdentity identity;
    @InjectMock MemoryRepository memories;
    @InjectMock ScopeRepository scopes;
    @InjectMock WritePolicyResolver policyResolver;

    @BeforeEach
    void setUp() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn("caller-sub");
        when(identity.getPrincipal()).thenReturn(jwt);
        // FEAT-19: the remember/forget tool paths now resolve the target scope
        // (ScopeRepository.requireBySlug) for the scope-lock pre-check before
        // delegating to the repository. Default every slug to an open (unlocked)
        // scope; the unknown-scope tests override requireBySlug to throw.
        lenient().when(scopes.requireBySlug(anyString()))
            .thenAnswer(i -> scope(i.getArgument(0), ScopeKind.PROJECT));
    }

    // ---------- helpers ------------------------------------------------------

    private static Scope scope(String slug, ScopeKind kind) {
        Scope s = new Scope();
        s.id = UUID.randomUUID();
        s.slug = slug;
        s.name = slug.substring(0, 1).toUpperCase() + slug.substring(1);
        s.kind = kind;
        s.description = null;
        s.fixed = kind != ScopeKind.PROJECT;
        s.archived = false;
        s.createdAt = Instant.now();
        return s;
    }

    private static Memory memory(MemoryType type, Scope sc, String key, String content) {
        Memory m = new Memory();
        m.logicalId = UUID.randomUUID();
        m.scope = sc;
        m.type = type;
        m.key = key;
        m.content = content;
        m.ownerSubject = "caller-sub";
        m.source = SourceChannel.MCP;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }

    private Resolved resolved(WritePolicy stored, WritePolicy effective,
                              DefaultScopeStatus status, String defaultSlug) {
        return new Resolved(stored, effective, status, defaultSlug);
    }

    // ---------- memory_remember: explicit scope happy path ------------------

    @Test
    void remember_explicitScope_keyNull_callsRememberAndReturnsResult() {
        Scope projectScope = scope("alpha", ScopeKind.PROJECT);
        Memory persisted = memory(MemoryType.DECISION, projectScope, null, "we ship daily");
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.PROJECT, DefaultScopeStatus.OK, "alpha"));
        when(memories.remember(
            eq("caller-sub"), eq("alpha"), eq(MemoryType.DECISION),
            isNull(), eq("we ship daily"), eq(SourceChannel.MCP)))
            .thenReturn(persisted);

        Dtos.RememberResult out = tools.memory_remember("we ship daily", "decision", "alpha", null, null);

        assertThat(out.memory()).isNotNull();
        assertThat(out.memory().content()).isEqualTo("we ship daily");
        // No prompt — explicit scope means the policy is not the gate.
        assertThat(out.prompt()).isNull();
        // dogfood-11: the policy receipt is omitted on a successful write — the real
        // scope is already in MemoryDto; policy is decision-bearing only on the prompt.
        assertThat(out.policy()).isNull();
        verify(memories).remember(any(), eq("alpha"), any(), any(), any(), eq(SourceChannel.MCP));
    }

    // ---------- memory_remember: input validation → clean tool error ---------
    // Regression: a client-input rejection (e.g. content > 1500) used to throw a
    // JAX-RS BadRequestException / IllegalArgumentException, which the MCP
    // framework collapsed into a bare {-32603 "Internal error", data:null}. It
    // must instead surface as a ToolCallException → ToolResponse.error(message)
    // so the assistant sees the reason and can correct.

    @Test
    void remember_unknownType_throwsToolCallExceptionWithReason() {
        assertThatThrownBy(() -> tools.memory_remember("ok", "not-a-type", "alpha", null, null))
            .isInstanceOf(ToolCallException.class)
            .hasMessageContaining("unknown memory type");
    }

    @Test
    void forget_malformedUuid_throwsToolCallException_notInternalError() {
        assertThatThrownBy(() -> tools.memory_forget("alpha", "not-a-uuid", null))
            .isInstanceOf(ToolCallException.class);
    }

    // dogfood-14: an unknown/RLS-invisible scope slug resolves via
    // ScopeRepository.requireBySlug and throws ScopeNotFoundException —
    // previously uncaught → bare -32603. It must surface as a typed
    // ToolCallException naming the slug. (FEAT-19: the resolve now happens in the
    // tool for the scope-lock pre-check, ahead of memories.remember/forget; the
    // ScopeNotFound path is identical.)

    @Test
    void remember_unknownScope_throwsToolCallException_notInternalError() {
        when(scopes.requireBySlug("nope"))
            .thenThrow(new ScopeRepository.ScopeNotFoundException("scope not found: nope"));

        assertThatThrownBy(() -> tools.memory_remember("x", "decision", "nope", null, null))
            .isInstanceOf(ToolCallException.class)
            .hasMessageContaining("nope")
            .hasMessageContaining("does not exist or is not visible");
    }

    @Test
    void forget_unknownScope_throwsToolCallException_notInternalError() {
        when(scopes.requireBySlug("nope"))
            .thenThrow(new ScopeRepository.ScopeNotFoundException("scope not found: nope"));

        assertThatThrownBy(() -> tools.memory_forget("nope", null, "k"))
            .isInstanceOf(ToolCallException.class)
            .hasMessageContaining("nope")
            .hasMessageContaining("does not exist or is not visible");
    }

    // ---------- memory_remember: keyed upsert existence check ----------------

    @Test
    void remember_withKey_existenceCheckBindsNoTenantIdByHand() {
        Scope projectScope = scope("alpha", ScopeKind.PROJECT);
        Memory persisted = memory(MemoryType.DECISION, projectScope, "release.notes", "ship it");
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.PROJECT, DefaultScopeStatus.OK, "alpha"));

        // The keyed path runs an existence probe before the upsert. Regression:
        // it must let the @TenantId discriminator handle tenant isolation —
        // binding tenant_id by hand bound a UUID to the String discriminator and
        // 500'd every keyed write (and in SaaS used the zero-sentinel, not the
        // request tenant). V16 (A1.3 (1)): for a SHARED scope ("alpha" = project)
        // the probe is author-independent (scope.slug, key) — no ownerSubject —
        // mirroring the new shared upsert lookup; private keeps (scope, owner, key).
        @SuppressWarnings("unchecked")
        PanacheQuery<Memory> existing = mock(PanacheQuery.class);
        when(existing.firstResultOptional()).thenReturn(Optional.of(persisted));
        // find(String, Object...) — match the varargs as a whole with
        // any(Object[].class); capturing the query string happens in verify.
        when(memories.find(anyString(), any(Object[].class))).thenReturn(existing);
        when(memories.remember(
            eq("caller-sub"), eq("alpha"), eq(MemoryType.DECISION),
            eq("release.notes"), eq("ship it"), eq(SourceChannel.MCP)))
            .thenReturn(persisted);

        Dtos.RememberResult out = tools.memory_remember("ship it", "decision", "alpha", "release.notes", null);

        assertThat(out.upserted()).isTrue();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(memories).find(query.capture(), any(Object[].class));
        assertThat(query.getValue())
            .doesNotContain("tenantId", "ownerSubject")   // shared probe is author-independent (V16)
            .contains("scope.slug", "key");
    }

    @Test
    void remember_withKey_privateScope_existenceProbeStaysPerAuthor() {
        // V16 (A1.3 (1)): the PRIVATE scope keeps a per-author keyspace, so the
        // existed-probe for the reserved "private" slug stays (scope, owner, key).
        Scope priv = scope("private", ScopeKind.PRIVATE);
        Memory persisted = memory(MemoryType.DECISION, priv, "note.k", "secret");
        @SuppressWarnings("unchecked")
        PanacheQuery<Memory> existing = mock(PanacheQuery.class);
        when(existing.firstResultOptional()).thenReturn(Optional.of(persisted));
        when(memories.find(anyString(), any(Object[].class))).thenReturn(existing);
        when(memories.remember(
            eq("caller-sub"), eq("private"), eq(MemoryType.DECISION),
            eq("note.k"), eq("secret"), eq(SourceChannel.MCP)))
            .thenReturn(persisted);

        Dtos.RememberResult out = tools.memory_remember("secret", "decision", "private", "note.k", null);

        assertThat(out.upserted()).isTrue();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(memories).find(query.capture(), any(Object[].class));
        assertThat(query.getValue())
            .doesNotContain("tenantId")
            .contains("scope.slug", "ownerSubject", "key");
    }

    // ---------- memory_remember: implicit scope via policy ------------------

    @Test
    void remember_noScope_policyProject_usesDefaultScopeSlug() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.PROJECT, DefaultScopeStatus.OK, "alpha"));
        Scope alpha = scope("alpha", ScopeKind.PROJECT);
        when(memories.remember(any(), eq("alpha"), any(), any(), any(), any()))
            .thenReturn(memory(MemoryType.CONVENTION, alpha, null, "x"));

        Dtos.RememberResult out = tools.memory_remember("x", "convention", null, null, null);

        assertThat(out.memory()).isNotNull();
        // dogfood-11: policy-resolved (PROJECT) success also omits the policy receipt.
        assertThat(out.policy()).isNull();
        verify(memories).remember(any(), eq("alpha"), eq(MemoryType.CONVENTION), any(), any(), any());
    }

    @Test
    void remember_noScope_policyGlobal_usesGlobalSlug() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.GLOBAL, WritePolicy.GLOBAL, DefaultScopeStatus.OK, null));
        Scope g = scope("global", ScopeKind.GLOBAL);
        when(memories.remember(any(), eq("global"), any(), any(), any(), any()))
            .thenReturn(memory(MemoryType.CONSTRAINT, g, null, "y"));

        Dtos.RememberResult out = tools.memory_remember("y", "constraint", null, null, null);

        assertThat(out.memory()).isNotNull();
        // dogfood-11: policy-resolved (GLOBAL) success also omits the policy receipt.
        assertThat(out.policy()).isNull();
        verify(memories).remember(any(), eq("global"), eq(MemoryType.CONSTRAINT), any(), any(), any());
    }

    // ---------- memory_remember: ASK → structured prompt, NEVER private fallback

    @Test
    void remember_noScope_policyAskOK_returnsPromptWithVisibleScopes() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.ASK, WritePolicy.ASK, DefaultScopeStatus.OK, null));
        // Private scope MUST NOT be in the prompt list (D3/handoff §F-2).
        when(scopes.listAll()).thenReturn(List.of(
            scope("global", ScopeKind.GLOBAL),
            scope("alpha", ScopeKind.PROJECT),
            scope("personal", ScopeKind.PRIVATE)
        ));

        Dtos.RememberResult out = tools.memory_remember("x", "decision", null, null, null);

        assertThat(out.memory()).isNull();
        assertThat(out.prompt()).isNotNull();
        // dogfood-11: on the prompt-for-scope path the policy receipt is retained —
        // here it is genuinely decision-bearing (explains why a scope is required).
        assertThat(out.policy()).isNotNull();
        assertThat(out.prompt().reason()).contains("write policy is 'ask'");
        assertThat(out.prompt().available())
            .extracting(Dtos.ScopeDto::slug)
            .containsExactlyInAnyOrder("global", "alpha");
        // Service never reached.
        verify(memories, org.mockito.Mockito.never())
            .remember(any(), any(), any(), any(), any(), any());
    }

    @Test
    void remember_noScope_policyAskBecauseDefaultMissing_promptExplainsWhy() {
        when(policyResolver.resolve()).thenReturn(
            // Stored policy is PROJECT but effective falls back to ASK because
            // the default scope is missing entirely.
            resolved(WritePolicy.PROJECT, WritePolicy.ASK, DefaultScopeStatus.MISSING, null));
        when(scopes.listAll()).thenReturn(List.of(scope("global", ScopeKind.GLOBAL)));

        Dtos.RememberResult out = tools.memory_remember("x", "decision", null, null, null);

        assertThat(out.prompt()).isNotNull();
        assertThat(out.prompt().reason())
            .contains("'project'")
            .contains("no default scope is set");
    }

    @Test
    void remember_noScope_policyAskBecauseArchived_promptExplainsWhy() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.ASK, DefaultScopeStatus.ARCHIVED, "archived-one"));
        when(scopes.listAll()).thenReturn(List.of());

        Dtos.RememberResult out = tools.memory_remember("x", "decision", null, null, null);
        assertThat(out.prompt().reason()).contains("archived");
    }

    @Test
    void remember_noScope_policyAskBecauseInvalid_promptExplainsWhy() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.ASK, DefaultScopeStatus.INVALID, "was-private"));
        when(scopes.listAll()).thenReturn(List.of());

        Dtos.RememberResult out = tools.memory_remember("x", "decision", null, null, null);
        assertThat(out.prompt().reason()).contains("no longer a project");
    }

    // ---------- memory_recall ------------------------------------------------

    @Test
    void recall_forwardsAllFiltersAndReturnsView() {
        Scope g = scope("global", ScopeKind.GLOBAL);
        Memory m = memory(MemoryType.STATUS, g, "k1", "currently green");
        when(memories.recall(eq("caller-sub"), eq("global"), eq(MemoryType.STATUS),
                             eq("green"), eq(true)))
            .thenReturn(List.of(m));

        Dtos.RecallResult out = tools.memory_recall("global", "status", "green", true);

        assertThat(out.memories()).hasSize(1);
        assertThat(out.memories().get(0).content()).isEqualTo("currently green");
    }

    @Test
    void recall_includeGlobalNull_treatedAsFalse() {
        // Default-false guard for include_global is part of the contract.
        when(memories.recall(any(), any(), any(), any(), eq(false))).thenReturn(List.of());
        tools.memory_recall(null, null, null, null);
        verify(memories).recall(any(), isNull(), isNull(), isNull(), eq(false));
    }

    @Test
    void recall_typeNull_passesNullToRepo() {
        when(memories.recall(any(), any(), any(), any(), anyBoolean())).thenReturn(List.of());
        tools.memory_recall(null, null, null, false);
        verify(memories).recall(any(), any(), isNull(), any(), anyBoolean());
    }

    // ---------- memory_forget ------------------------------------------------

    @Test
    void forget_byIdUuid_passesParsedUuid() {
        UUID id = UUID.randomUUID();
        when(memories.forget(eq("caller-sub"), eq("alpha"), eq(id), isNull())).thenReturn(1);

        Dtos.ForgetResult out = tools.memory_forget("alpha", id.toString(), null);
        assertThat(out.deleted()).isEqualTo(1);
    }

    @Test
    void forget_byKey_passesNullId() {
        when(memories.forget(eq("caller-sub"), eq("alpha"), isNull(), eq("trust-region")))
            .thenReturn(2);

        Dtos.ForgetResult out = tools.memory_forget("alpha", null, "trust-region");
        assertThat(out.deleted()).isEqualTo(2);
    }

    @Test
    void forget_blankId_treatedAsNull() {
        when(memories.forget(eq("caller-sub"), eq("alpha"), isNull(), eq("k"))).thenReturn(0);
        tools.memory_forget("alpha", "  ", "k");
        verify(memories).forget(any(), any(), isNull(), eq("k"));
    }

    // ---------- memory_scopes ------------------------------------------------

    @Test
    void scopes_returnsAllScopesMapped() {
        when(scopes.listAll()).thenReturn(List.of(
            scope("global", ScopeKind.GLOBAL),
            scope("alpha", ScopeKind.PROJECT),
            scope("personal", ScopeKind.PRIVATE)
        ));

        Dtos.ScopesResult out = tools.memory_scopes();
        // Unlike the prompt path, memory_scopes DOES surface private — the
        // caller's own private scope is part of "everything visible to me".
        assertThat(out.scopes())
            .extracting(Dtos.ScopeDto::slug)
            .containsExactlyInAnyOrder("global", "alpha", "personal");
    }

    // ---------- D-CORE-7: reference URL -------------------------------------

    @Test
    void remember_storesReferenceOnNewRow() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.GLOBAL, WritePolicy.GLOBAL, DefaultScopeStatus.OK, null));
        Scope g = scope("global", ScopeKind.GLOBAL);
        Memory persisted = memory(MemoryType.DECISION, g, null, "with a source");
        when(memories.remember(any(), eq("global"), any(), any(), any(), any())).thenReturn(persisted);

        Dtos.RememberResult out = tools.memory_remember(
            "with a source", "decision", "global", null, "https://example.com/spec#s3");

        assertThat(out.memory()).isNotNull();
        assertThat(out.memory().reference()).isEqualTo("https://example.com/spec#s3");
    }

    @Test
    void remember_rejectsCredentialBearingReference() {
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.GLOBAL, WritePolicy.GLOBAL, DefaultScopeStatus.OK, null));

        // MCP surface: a rejection is a clean ToolCallException (→ isError result),
        // not the raw IllegalArgumentException (which would collapse to -32603).
        assertThatThrownBy(() -> tools.memory_remember(
                "x", "decision", "global", null, "https://user:secret@example.com/x"))
            .isInstanceOf(ToolCallException.class);
        verify(memories, org.mockito.Mockito.never())
            .remember(any(), any(), any(), any(), any(), any());
    }

    @Test
    void remember_rejectsContentOverTheLengthLimit() {
        // F-1: the ≤1500-char contract is enforced server-side, before any
        // policy/repo work — a direct MCP caller cannot persist unbounded content.
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.GLOBAL, WritePolicy.GLOBAL, DefaultScopeStatus.OK, null));

        // MCP surface: surfaced as a ToolCallException carrying the reason, not a
        // raw BadRequestException (which the framework would turn into -32603).
        assertThatThrownBy(() -> tools.memory_remember(
                "a".repeat(1501), "decision", "global", null, null))
            .isInstanceOf(ToolCallException.class)
            .hasMessageContaining("1500");
        verify(memories, org.mockito.Mockito.never())
            .remember(any(), any(), any(), any(), any(), any());
    }

    @Test
    void loadContext_digestOmitsReference() {
        // D-CORE-7 guard 2: the digest is lean — the reference URL is verify-on-demand
        // (surfaced by memory_recall), never in the load_context bulk.
        Scope g = scope("global", ScopeKind.GLOBAL);
        Memory dec = memory(MemoryType.DECISION, g, "d", "decided X");
        dec.reference = "https://example.com/why";
        when(memories.loadContext(eq("caller-sub"), isNull(), isNull()))
            .thenReturn(Map.of(MemoryType.DECISION, List.of(dec)));

        Dtos.LoadContextResult out = tools.memory_load_context(null, null);

        assertThat(out.byType().get("decision")).hasSize(1);
        assertThat(out.byType().get("decision").get(0).reference()).isNull();
    }

    // ---------- memory_load_context -----------------------------------------

    @Test
    void loadContext_defaultExcludesOpenQuestion_canonicalOrder() {
        // D-CORE-6: the default digest is the steering types only — no open_question
        // bucket. The tool asks the repo with a null type set (= steering default),
        // and emits keys in canonical order for the digested types only.
        Scope g = scope("global", ScopeKind.GLOBAL);
        Memory dec = memory(MemoryType.DECISION, g, "d", "decided X");
        Memory con = memory(MemoryType.CONSTRAINT, g, "c", "no PII in logs");
        Memory glo = memory(MemoryType.GLOSSARY, g, "term", "RLS = Row-Level Security");

        when(memories.loadContext(eq("caller-sub"), isNull(), isNull())).thenReturn(Map.of(
            MemoryType.DECISION, List.of(dec),
            MemoryType.CONSTRAINT, List.of(con),
            MemoryType.CONVENTION, List.of(),
            MemoryType.GLOSSARY, List.of(glo),
            MemoryType.STATUS, List.of()
        ));

        Dtos.LoadContextResult out = tools.memory_load_context(null, null);

        assertThat(out.total()).isEqualTo(3);
        assertThat(out.byType().keySet())
            .containsExactly("decision", "constraint", "convention", "glossary", "status");
        assertThat(out.byType()).doesNotContainKey("open_question");
        assertThat(out.byType().get("constraint").get(0).content()).isEqualTo("no PII in logs");
    }

    @Test
    void loadContext_explicitTypes_includesOpenQuestion() {
        // Passing an explicit type set (e.g. to review open questions) digests exactly
        // those types — the tool parses the comma list into the type set the repo gets.
        Scope g = scope("global", ScopeKind.GLOBAL);
        Memory dec = memory(MemoryType.DECISION, g, "d", "decided X");
        Memory oq = memory(MemoryType.OPEN_QUESTION, g, "q", "still open?");

        when(memories.loadContext(eq("caller-sub"), isNull(),
                eq(java.util.EnumSet.of(MemoryType.DECISION, MemoryType.OPEN_QUESTION))))
            .thenReturn(Map.of(
                MemoryType.DECISION, List.of(dec),
                MemoryType.OPEN_QUESTION, List.of(oq)));

        Dtos.LoadContextResult out = tools.memory_load_context(null, "decision, open_question");

        assertThat(out.total()).isEqualTo(2);
        assertThat(out.byType().keySet()).containsExactly("decision", "open_question");
        assertThat(out.byType().get("open_question").get(0).content()).isEqualTo("still open?");
    }

    @Test
    void loadContext_emptyResult_returnsNoBuckets() {
        when(memories.loadContext(any(), any(), any())).thenReturn(Map.of());

        Dtos.LoadContextResult out = tools.memory_load_context("alpha", null);

        assertThat(out.total()).isZero();
        assertThat(out.byType()).isEmpty();
    }

    // ---------- memory_remember: D-CORE-11 protected-key upsert -------------

    @Test
    void remember_targetingProtectedKey_returnsTypedError_notARawThrow() {
        // D-CORE-11: the repo raises ProtectedEntryException(UPSERT_BLOCKED).
        // MemoryTools must translate to a typed ProtectedError in the DTO
        // so the MCP framework returns a structured result instead of -32603.
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.PROJECT,
                     DefaultScopeStatus.OK, "alpha"));

        // The keyed path runs an existence query before the upsert; stub
        // it to return empty (use any(Object[].class) for the varargs).
        @SuppressWarnings("unchecked")
        PanacheQuery<Memory> emptyQ = mock(PanacheQuery.class);
        when(emptyQ.firstResultOptional()).thenReturn(Optional.empty());
        when(memories.find(anyString(), any(Object[].class))).thenReturn(emptyQ);

        when(memories.remember(
                eq("caller-sub"), eq("alpha"), eq(MemoryType.CONVENTION),
                eq("convention.how-to-kumbuka.types"), anyString(), eq(SourceChannel.MCP)))
            .thenThrow(new ai.kumbuka.repo.ProtectedEntryException(
                ai.kumbuka.repo.ProtectedEntryException.Reason.UPSERT_BLOCKED,
                "convention.how-to-kumbuka.types",
                "key reserved by a protected system-seed entry"));

        Dtos.RememberResult out = tools.memory_remember(
            "spoof", "convention", "alpha",
            "convention.how-to-kumbuka.types", null);

        assertThat(out.memory()).isNull();
        assertThat(out.error()).isNotNull();
        assertThat(out.error().code()).isEqualTo("PROTECTED_UPSERT_BLOCKED");
        assertThat(out.error().key()).isEqualTo("convention.how-to-kumbuka.types");
        assertThat(out.error().message()).contains("protected");
    }

    // ---------- memory_forget: D-CORE-11 protected delete-block -------------

    @Test
    void forget_protectedRow_returnsTypedDeleteBlocked_notARawThrow() {
        Scope shared = scope("alpha", ScopeKind.PROJECT);
        when(scopes.requireBySlug("alpha")).thenReturn(shared);
        when(memories.forget(eq("caller-sub"), eq("alpha"), isNull(), eq("seed.key")))
            .thenThrow(new ai.kumbuka.repo.ProtectedEntryException(
                ai.kumbuka.repo.ProtectedEntryException.Reason.DELETE_BLOCKED,
                "seed.key",
                "delete blocked: row carries protected = true"));

        Dtos.ForgetResult out = tools.memory_forget("alpha", null, "seed.key");

        assertThat(out.deleted()).isZero();
        assertThat(out.error()).isNotNull();
        assertThat(out.error().code()).isEqualTo("PROTECTED_DELETE_BLOCKED");
        assertThat(out.error().key()).isEqualTo("seed.key");
    }

    // ---------- memory_remember: D-CORE-11 + E2E-06 key-format validation ---

    @Test
    void remember_rejectsUnderscoreKey_atTheToolLayer_noRepoCall() {
        // E2E-06: the underscore-key WORKLIST regression. Pre-validator the
        // call would persist (the DB CHECK would only fire much later for a
        // genuine bad input — the regex in V2 allows it through because
        // [a-z0-9_]?? actually no, V2 already rejects underscores at the
        // DB layer. The validator gives a clean tool-layer rejection instead of
        // a constraint-violation 500 — surfaced as a ToolCallException on MCP.
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.PROJECT,
                     DefaultScopeStatus.OK, "alpha"));

        assertThatThrownBy(() ->
                tools.memory_remember("ok", "decision", "alpha", "test_1", null))
            .isInstanceOf(ToolCallException.class)
            .hasMessageContaining("test_1");

        // Validator fires BEFORE the repo call — no row attempt at all.
        verify(memories, org.mockito.Mockito.never())
            .remember(any(), any(), any(), any(), any(), any());
    }

    @Test
    void remember_acceptsDotKebabKey() {
        Scope projectScope = scope("alpha", ScopeKind.PROJECT);
        Memory persisted = memory(MemoryType.DECISION, projectScope,
            "decision.d-ops-26", "shipped");
        when(policyResolver.resolve()).thenReturn(
            resolved(WritePolicy.PROJECT, WritePolicy.PROJECT,
                     DefaultScopeStatus.OK, "alpha"));
        @SuppressWarnings("unchecked")
        PanacheQuery<Memory> emptyQ = mock(PanacheQuery.class);
        when(emptyQ.firstResultOptional()).thenReturn(Optional.empty());
        when(memories.find(anyString(), any(Object[].class))).thenReturn(emptyQ);
        when(memories.remember(
                eq("caller-sub"), eq("alpha"), eq(MemoryType.DECISION),
                eq("decision.d-ops-26"), anyString(), eq(SourceChannel.MCP)))
            .thenReturn(persisted);

        Dtos.RememberResult out = tools.memory_remember(
            "shipped", "decision", "alpha", "decision.d-ops-26", null);

        assertThat(out.memory()).isNotNull();
        assertThat(out.memory().key()).isEqualTo("decision.d-ops-26");
    }
}
