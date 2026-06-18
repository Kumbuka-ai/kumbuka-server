package ai.kumbuka.admin;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.mcp.MuteTestSupport;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/scopes/{slug}/entries — memory entries inside a shared scope.
 * Member-readable and member-writable: the role gate admits members, then
 * {@link ai.kumbuka.service.MemberWritePolicy} decides at runtime (normal
 * member writes, muted member rejected — D-CORE-2), mirroring the MCP write
 * tools. Private scope addressing → 404 across every method.
 */
@QuarkusTest
class AdminEntriesResourceTest {

    @InjectMock ScopeRepository scopes;
    @InjectMock MemoryRepository memories;
    @InjectMock SharedMemoryRepository sharedMemories;
    @InjectMock ai.kumbuka.audit.TeamAuditService audit;   // D-CORE-17 remap audit
    @Inject MuteTestSupport mute;   // seeds a real user_account.muted row (D-CORE-2)

    private Scope sharedScope(String slug) {
        Scope s = new Scope();
        s.id = UUID.randomUUID();
        s.slug = slug;
        s.name = slug;
        s.kind = ScopeKind.PROJECT;
        s.archived = false;
        return s;
    }

    private Memory mem(Scope sc, MemoryType type, String key, String content) {
        Memory m = new Memory();
        m.id = UUID.randomUUID();
        m.scope = sc;
        m.type = type;
        m.key = key;
        m.content = content;
        m.ownerSubject = "admin";
        m.source = SourceChannel.CONSOLE;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }

    // ---------- list ---------------------------------------------------------

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void list_member_returnsEntries() {
        Scope alpha = sharedScope("alpha");
        Memory m = mem(alpha, MemoryType.DECISION, "k", "we ship daily");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        when(sharedMemories.listShared("alpha", null)).thenReturn(List.of(m));

        given()
            .when().get("/api/scopes/alpha/entries")
            .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].content", equalTo("we ship daily"))
                .body("[0].source", equalTo("console"));
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void list_privateSlug_returns404() {
        Scope priv = sharedScope("personal");
        priv.kind = ScopeKind.PRIVATE;
        when(scopes.requireBySlug("personal")).thenReturn(priv);

        given()
            .when().get("/api/scopes/personal/entries")
            .then().statusCode(404);
    }

    // ---------- create ------------------------------------------------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_admin_returnsCreated() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        Memory created = mem(alpha, MemoryType.CONVENTION, "naming", "use kebab-case");
        when(memories.createShared(eq("admin"), eq("alpha"), eq(MemoryType.CONVENTION),
                               eq("naming"), eq("use kebab-case"), eq(SourceChannel.CONSOLE)))
            .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "convention", "key": "naming", "content": "use kebab-case"}
                """)
            .when().post("/api/scopes/alpha/entries")
            .then()
                .statusCode(201)
                .body("content", equalTo("use kebab-case"))
                .body("source", equalTo("console"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_contentOverLimit_returns400() {
        // F-1: server-side ≤1500-char enforcement on the admin write path.
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        given()
            .contentType(ContentType.JSON)
            .body("{\"type\": \"decision\", \"content\": \"" + "a".repeat(1501) + "\"}")
            .when().post("/api/scopes/alpha/entries")
            .then().statusCode(400);

        verify(memories, org.mockito.Mockito.never())
            .createShared(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    @TestSecurity(user = "member-writer", roles = {"member"})
    void create_member_nonLocked_returnsCreated() {
        // D-CORE-2: a normal (non-muted) member is entitled to shared writes —
        // the console path must match the MCP path. Parity with
        // MemoryToolsMuteTest#unmutedMember_canRememberToSharedScope.
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        Memory created = mem(alpha, MemoryType.DECISION, "k", "members write shared");
        when(memories.createShared(eq("member-writer"), eq("alpha"), eq(MemoryType.DECISION),
                               eq("k"), eq("members write shared"), eq(SourceChannel.CONSOLE)))
            .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "decision", "key": "k", "content": "members write shared"}
                """)
            .when().post("/api/scopes/alpha/entries")
            .then()
                .statusCode(201)
                .body("content", equalTo("members write shared"));
    }

    @Test
    @TestSecurity(user = "muted-creator", roles = {"member"})
    void create_mutedMember_isForbidden_noWrite() {
        // A muted member is rejected at the runtime gate, before any write —
        // parity with the MCP-side D-CORE-2 mute tests.
        mute.setMuted("muted-creator", true);
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "decision", "content": "x"}
                """)
            .when().post("/api/scopes/alpha/entries")
            .then().statusCode(403);

        verify(memories, org.mockito.Mockito.never())
            .createShared(any(), anyString(), any(), any(), any(), any());
    }

    // ---------- update -------------------------------------------------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_inScope_returnsUpdatedView() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory updated = mem(alpha, MemoryType.STATUS, null, "green");
        updated.id = id;
        when(sharedMemories.update(eq(id), eq("green"), eq(MemoryType.STATUS)))
            .thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "status", "content": "green"}
                """)
            .when().patch("/api/scopes/alpha/entries/" + id)
            .then()
                .statusCode(200)
                .body("content", equalTo("green"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_nullType_passesNullToRepo() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory updated = mem(alpha, MemoryType.DECISION, null, "v2");
        updated.id = id;
        when(sharedMemories.update(eq(id), any(), eq(null))).thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"content": "v2"}
                """)
            .when().patch("/api/scopes/alpha/entries/" + id)
            .then().statusCode(200);

        verify(sharedMemories).update(id, "v2", null);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_contentOverLimit_returns400() {
        // F-1: the same ≤1500 guard applies on update.
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();

        given()
            .contentType(ContentType.JSON)
            .body("{\"content\": \"" + "a".repeat(1501) + "\"}")
            .when().patch("/api/scopes/alpha/entries/" + id)
            .then().statusCode(400);

        verify(sharedMemories, org.mockito.Mockito.never()).update(any(), any(), any());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_entryInDifferentScope_returns404() {
        Scope alpha = sharedScope("alpha");
        Scope beta = sharedScope("beta");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory inBeta = mem(beta, MemoryType.DECISION, null, "wrong-scope");
        inBeta.id = id;
        when(sharedMemories.update(eq(id), anyString(), any())).thenReturn(inBeta);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"content": "x"}
                """)
            .when().patch("/api/scopes/alpha/entries/" + id)
            .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "member-editor", roles = {"member"})
    void update_member_nonLocked_returns200() {
        // D-CORE-2: a normal member may edit a non-locked shared entry.
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory updated = mem(alpha, MemoryType.STATUS, null, "green");
        updated.id = id;
        when(sharedMemories.update(eq(id), eq("green"), eq(MemoryType.STATUS)))
            .thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "status", "content": "green"}
                """)
            .when().patch("/api/scopes/alpha/entries/" + id)
            .then()
                .statusCode(200)
                .body("content", equalTo("green"));
    }

    @Test
    @TestSecurity(user = "muted-editor", roles = {"member"})
    void update_mutedMember_isForbidden_noWrite() {
        mute.setMuted("muted-editor", true);
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"content": "x"}
                """)
            .when().patch("/api/scopes/alpha/entries/" + UUID.randomUUID())
            .then().statusCode(403);

        verify(sharedMemories, org.mockito.Mockito.never()).update(any(), any(), any());
    }

    // ---------- delete -------------------------------------------------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void delete_admin_inScope_returns204() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory existing = mem(alpha, MemoryType.DECISION, null, "x");
        existing.id = id;
        when(sharedMemories.findSharedById(id)).thenReturn(existing);

        given()
            .when().delete("/api/scopes/alpha/entries/" + id)
            .then().statusCode(204);

        verify(sharedMemories).deleteShared(id);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void delete_unknownEntry_returns404_noDelete() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        when(sharedMemories.findSharedById(id)).thenReturn(null);

        given()
            .when().delete("/api/scopes/alpha/entries/" + id)
            .then().statusCode(404);

        verify(sharedMemories, org.mockito.Mockito.never()).deleteShared(any());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void delete_entryInDifferentScope_returns404_noDelete() {
        Scope alpha = sharedScope("alpha");
        Scope beta = sharedScope("beta");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory inBeta = mem(beta, MemoryType.DECISION, null, "wrong");
        inBeta.id = id;
        when(sharedMemories.findSharedById(id)).thenReturn(inBeta);

        given()
            .when().delete("/api/scopes/alpha/entries/" + id)
            .then().statusCode(404);

        verify(sharedMemories, org.mockito.Mockito.never()).deleteShared(any());
    }

    @Test
    @TestSecurity(user = "member-deleter", roles = {"member"})
    void delete_member_nonLocked_returns204() {
        // D-CORE-2: a normal member may delete a non-locked shared entry.
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        UUID id = UUID.randomUUID();
        Memory existing = mem(alpha, MemoryType.DECISION, null, "x");
        existing.id = id;
        when(sharedMemories.findSharedById(id)).thenReturn(existing);

        given()
            .when().delete("/api/scopes/alpha/entries/" + id)
            .then().statusCode(204);

        verify(sharedMemories).deleteShared(id);
    }

    @Test
    @TestSecurity(user = "muted-deleter", roles = {"member"})
    void delete_mutedMember_isForbidden_noDelete() {
        mute.setMuted("muted-deleter", true);
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        given()
            .when().delete("/api/scopes/alpha/entries/" + UUID.randomUUID())
            .then().statusCode(403);

        verify(sharedMemories, org.mockito.Mockito.never()).deleteShared(any());
    }

    // ---------- D-CORE-11: protected-entry surface via ExceptionMapper ------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_targetingProtectedKey_returnsTypedHttp409_viaMapper() {
        // The mock raises ProtectedEntryException; the @Provider mapper
        // (ProtectedEntryExceptionMapper) must translate to 409 with a
        // typed body. Coverage: exercises the mapper through the real
        // REST pipeline so its auto-registration as @Provider gets
        // verified end-to-end.
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        when(memories.createShared(anyString(), eq("alpha"), any(),
                               eq("convention.how-to-kumbuka.types"), anyString(), any()))
            .thenThrow(new ai.kumbuka.repo.ProtectedEntryException(
                ai.kumbuka.repo.ProtectedEntryException.Reason.UPSERT_BLOCKED,
                "convention.how-to-kumbuka.types",
                "key reserved by a protected system-seed entry"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "convention", "key": "convention.how-to-kumbuka.types", "content": "spoof"}
                """)
            .when().post("/api/scopes/alpha/entries")
            .then()
                .statusCode(409)
                .body("code", equalTo("PROTECTED_UPSERT_BLOCKED"))
                .body("key",  equalTo("convention.how-to-kumbuka.types"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void delete_protectedRow_returnsTypedHttp409_viaMapper() {
        Scope alpha = sharedScope("alpha");
        Memory m = mem(alpha, MemoryType.CONVENTION, "convention.how-to-kumbuka.writing", "...");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        when(sharedMemories.findSharedById(m.id)).thenReturn(m);
        org.mockito.Mockito.doThrow(new ai.kumbuka.repo.ProtectedEntryException(
                ai.kumbuka.repo.ProtectedEntryException.Reason.DELETE_BLOCKED,
                null,
                "delete blocked: row id=" + m.id + " is protected (D-CORE-11)"))
            .when(sharedMemories).deleteShared(m.id);

        given()
            .when().delete("/api/scopes/alpha/entries/" + m.id)
            .then()
                .statusCode(409)
                .body("code", equalTo("PROTECTED_DELETE_BLOCKED"));
    }

    // ---------- D-CORE-16: console create rejects an existing key ------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_existingKey_returns409_keyExists_viaMapper() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        when(memories.createShared(anyString(), eq("alpha"), any(), eq("k.dup"), anyString(), any()))
            .thenThrow(new MemoryRepository.KeyExistsException(
                "k.dup", "an entry with key 'k.dup' already exists in scope 'alpha'."));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"type": "decision", "key": "k.dup", "content": "second"}
                """)
            .when().post("/api/scopes/alpha/entries")
            .then()
                .statusCode(409)
                .body("code", equalTo("KEY_EXISTS"));
    }

    // ---------- D-CORE-17: scope-remap endpoint -----------------------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void remap_admin_movesEntry_writesAudit() {
        Scope alpha = sharedScope("alpha");
        Scope beta = sharedScope("beta");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        Memory m = mem(alpha, MemoryType.DECISION, "k.move", "content");
        when(sharedMemories.findSharedById(m.id)).thenReturn(m);
        Memory moved = mem(beta, MemoryType.DECISION, "k.move", "content");
        when(memories.remap(any(), eq("beta"), any())).thenReturn(moved);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("""
                {"targetScope": "beta"}
                """)
            .when().post("/api/scopes/alpha/entries/" + m.id + ":remap")
            .then().statusCode(200);

        verify(memories).remap(any(), eq("beta"), any());
        verify(audit).append(eq("admin"), eq("scope.remap"), any(), any());
    }

    @Test
    @TestSecurity(user = "member-writer", roles = {"member"})
    void remap_member_isForbidden() {
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("""
                {"targetScope": "beta"}
                """)
            .when().post("/api/scopes/alpha/entries/" + UUID.randomUUID() + ":remap")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void remap_privateTarget_returns400_remapPrivateForbidden() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        Memory m = mem(alpha, MemoryType.DECISION, "k", "c");
        when(sharedMemories.findSharedById(m.id)).thenReturn(m);
        when(memories.remap(any(), eq("private"), any()))
            .thenThrow(new MemoryRepository.RemapPrivateForbiddenException(
                "private is not a remap endpoint (P1)."));

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("""
                {"targetScope": "private"}
                """)
            .when().post("/api/scopes/alpha/entries/" + m.id + ":remap")
            .then()
                .statusCode(400)
                .body("code", equalTo("REMAP_PRIVATE_FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void remap_unknownEntry_returns404() {
        Scope alpha = sharedScope("alpha");
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        when(sharedMemories.findSharedById(any())).thenReturn(null);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("""
                {"targetScope": "beta"}
                """)
            .when().post("/api/scopes/alpha/entries/" + UUID.randomUUID() + ":remap")
            .then().statusCode(404);
    }
}
