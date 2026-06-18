package ai.kumbuka.admin;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.TeamSettings.CreateScopes;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import ai.kumbuka.repo.TeamSettingsRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/scopes — scope CRUD over the admin REST API.
 *
 * Locks down:
 *   - listShared() never surfaces PRIVATE entries
 *   - POST honours the team's createScopes policy at runtime (D3): a
 *     member-rolled caller is rejected when ADMINS-only is configured,
 *     even though @RolesAllowed lets them past the gate
 *   - PATCH + archive reject /api/scopes/private addressing
 */
@QuarkusTest
class AdminScopesResourceTest {

    @InjectMock ScopeRepository scopes;
    @InjectMock SharedMemoryRepository sharedMemories;
    @InjectMock TeamSettingsRepository settings;

    private Scope scope(String slug, ScopeKind kind, boolean archived) {
        Scope s = new Scope();
        s.id = UUID.randomUUID();
        s.slug = slug;
        s.name = slug;
        s.kind = kind;
        s.fixed = kind != ScopeKind.PROJECT;
        s.archived = archived;
        s.createdAt = Instant.now();
        return s;
    }

    private TeamSettings withCreateScopes(CreateScopes mode) {
        TeamSettings ts = new TeamSettings();
        ts.setCreateScopes(mode);
        return ts;
    }

    // ---------- list ---------------------------------------------------------

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void list_member_returnsSharedScopesWithEntryCounts() {
        Scope alpha = scope("alpha", ScopeKind.PROJECT, false);
        Scope global = scope("global", ScopeKind.GLOBAL, false);
        when(scopes.listShared()).thenReturn(List.of(global, alpha));
        when(sharedMemories.listShared("alpha", null)).thenReturn(List.of(/* placeholder */));
        when(sharedMemories.listShared("global", null)).thenReturn(List.of());

        given()
            .when().get("/api/scopes")
            .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].slug", equalTo("global"))
                .body("[1].slug", equalTo("alpha"))
                .body("[1].entryCount", equalTo(0));
    }

    @Test
    void list_unauthenticated_isBlocked() {
        given().when().get("/api/scopes").then().statusCode(401);
    }

    // ---------- create ------------------------------------------------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_admin_returnsCreatedScope() {
        Scope created = scope("beta", ScopeKind.PROJECT, false);
        when(scopes.createProject(eq("beta"), eq("Beta"), any(), anyString())).thenReturn(created);
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.ADMINS));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "beta", "name": "Beta", "description": "second project"}
                """)
            .when().post("/api/scopes")
            .then()
                .statusCode(201)
                .body("slug", equalTo("beta"));
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void create_memberWithAdminsOnlyPolicy_isRejected403() {
        // Runtime policy gate kicks in EVEN THOUGH @RolesAllowed lets members past.
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.ADMINS));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "gamma", "name": "Gamma"}
                """)
            .when().post("/api/scopes")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void create_memberWithMembersPolicy_succeeds() {
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.MEMBERS));
        Scope created = scope("delta", ScopeKind.PROJECT, false);
        when(scopes.createProject(eq("delta"), any(), any(), anyString())).thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "delta", "name": "Delta"}
                """)
            .when().post("/api/scopes")
            .then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_blankSlug_rejectsAs400() {
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.ADMINS));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "  ", "name": "Whitespace"}
                """)
            .when().post("/api/scopes")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_missingName_rejectsAs400() {
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.ADMINS));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "beta"}
                """)
            .when().post("/api/scopes")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_trimsWhitespace_inSlugNameAndDescription() {
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.ADMINS));
        Scope created = scope("eps", ScopeKind.PROJECT, false);
        when(scopes.createProject(eq("eps"), eq("Eps"), eq("trimmed"), anyString()))
            .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "  eps  ", "name": "  Eps  ", "description": "  trimmed  "}
                """)
            .when().post("/api/scopes")
            .then().statusCode(201);

        verify(scopes).createProject("eps", "Eps", "trimmed", "admin");
    }

    // ---------- rename / archive --------------------------------------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void rename_existingShared_succeeds() {
        Scope alpha = scope("alpha", ScopeKind.PROJECT, false);
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);
        when(sharedMemories.listShared("alpha", null)).thenReturn(List.of());

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"name": "Alpha Renamed", "description": null}
                """)
            .when().patch("/api/scopes/alpha")
            .then().statusCode(200);

        verify(scopes).rename("alpha", "Alpha Renamed", null);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void rename_privateSlug_returns404() {
        Scope priv = scope("personal", ScopeKind.PRIVATE, false);
        when(scopes.requireBySlug("personal")).thenReturn(priv);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"name": "Renamed"}
                """)
            .when().patch("/api/scopes/personal")
            .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void archive_existingShared_returns204() {
        Scope alpha = scope("alpha", ScopeKind.PROJECT, false);
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        // The colon in `/{slug}:archive` needs urlEncodingEnabled(false) —
        // RestAssured otherwise %3A-encodes it and JAX-RS routes that to
        // the PATCH /{slug} handler instead.
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/alpha:archive")
            .then().statusCode(204);

        verify(scopes).archive("alpha");
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void archive_member_isForbidden() {
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/alpha:archive")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void archive_privateSlug_returns404() {
        Scope priv = scope("personal", ScopeKind.PRIVATE, false);
        when(scopes.requireBySlug("personal")).thenReturn(priv);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/personal:archive")
            .then().statusCode(404);
    }

    // ---------- dogfood-19: scope exceptions → typed 4xx (not 500) -----------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void create_duplicateSlug_returns409_scopeExists() {
        when(settings.current()).thenReturn(withCreateScopes(CreateScopes.ADMINS));
        when(scopes.createProject(eq("beta"), any(), any(), anyString()))
            .thenThrow(new ScopeRepository.ScopeAlreadyExistsException("scope already exists: beta"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"slug": "beta", "name": "Beta"}
                """)
            .when().post("/api/scopes")
            .then()
                .statusCode(409)
                .body("code", equalTo("SCOPE_EXISTS"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void archive_unknownSlug_returns404_scopeNotFound() {
        when(scopes.requireBySlug("ghost"))
            .thenThrow(new ScopeRepository.ScopeNotFoundException("scope not found: ghost"));

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/ghost:archive")
            .then()
                .statusCode(404)
                .body("code", equalTo("SCOPE_NOT_FOUND"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void archive_fixedGlobalScope_returns409_scopeLocked() {
        Scope global = scope("global", ScopeKind.GLOBAL, false);
        when(scopes.requireBySlug("global")).thenReturn(global);
        doThrow(new ScopeRepository.ScopeLockedException("fixed scope cannot be archived: global"))
            .when(scopes).archive("global");

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/global:archive")
            .then()
                .statusCode(409)
                .body("code", equalTo("SCOPE_LOCKED"));
    }

    // ---------- dogfood-16: un-archive (admin-only, reversible) -------------

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void unarchive_existingArchivedShared_returns204() {
        Scope alpha = scope("alpha", ScopeKind.PROJECT, true);
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/alpha:unarchive")
            .then().statusCode(204);

        verify(scopes).unarchive("alpha");
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void unarchive_member_isForbidden() {
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/alpha:unarchive")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void unarchive_privateSlug_returns404() {
        Scope priv = scope("personal", ScopeKind.PRIVATE, true);
        when(scopes.requireBySlug("personal")).thenReturn(priv);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/personal:unarchive")
            .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void unarchive_fixedGlobalScope_returns409_scopeLocked() {
        Scope global = scope("global", ScopeKind.GLOBAL, false);
        when(scopes.requireBySlug("global")).thenReturn(global);
        doThrow(new ScopeRepository.ScopeLockedException("fixed scope cannot be un-archived: global"))
            .when(scopes).unarchive("global");

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/global:unarchive")
            .then()
                .statusCode(409)
                .body("code", equalTo("SCOPE_LOCKED"));
    }
}
