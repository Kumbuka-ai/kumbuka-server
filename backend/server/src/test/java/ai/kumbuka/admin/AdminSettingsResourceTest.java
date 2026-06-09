package ai.kumbuka.admin;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.TeamSettings.CreateScopes;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.TeamSettingsRepository;
import ai.kumbuka.service.WritePolicyResolver;
import ai.kumbuka.service.WritePolicyResolver.DefaultScopeStatus;
import ai.kumbuka.service.WritePolicyResolver.Resolved;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * Tests /api/settings — D3 write-policy + createScopes management.
 *
 * The repositories are @InjectMock'd; the @Transactional dirty-check path
 * (entity-mutation auto-persist) is preserved because we return the same
 * TeamSettings mock instance on every settings.current() call.
 */
@QuarkusTest
class AdminSettingsResourceTest {

    @InjectMock TeamSettingsRepository settings;
    @InjectMock ScopeRepository scopes;
    @InjectMock WritePolicyResolver resolver;

    private TeamSettings ts;

    @BeforeEach
    void setUp() {
        ts = new TeamSettings();
        ts.setWritePolicy(WritePolicy.ASK);
        ts.setCreateScopes(CreateScopes.ADMINS);
        ts.defaultScopeId = null;

        when(settings.current()).thenReturn(ts);
        when(resolver.resolve()).thenReturn(
            new Resolved(WritePolicy.ASK, WritePolicy.ASK, DefaultScopeStatus.OK, null));
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void get_member_returnsSettingsView() {
        given()
            .when().get("/api/settings")
            .then()
                .statusCode(200)
                .body("writePolicy", equalTo("ask"))
                .body("effectiveWritePolicy", equalTo("ask"))
                .body("defaultScopeSlug", nullValue())
                .body("createScopes", equalTo("admins"));
    }

    @Test
    void get_unauthenticated_isBlocked() {
        given().when().get("/api/settings").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "u", roles = {"member"})
    void update_member_isForbidden() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"createScopes": "members"}
                """)
            .when().patch("/api/settings")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_createScopes_isPersistedAndReflected() {
        when(resolver.resolve()).thenReturn(
            new Resolved(WritePolicy.ASK, WritePolicy.ASK, DefaultScopeStatus.OK, null));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"createScopes": "members"}
                """)
            .when().patch("/api/settings")
            .then()
                .statusCode(200)
                .body("createScopes", equalTo("members"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_writePolicyProjectWithoutDefaultScope_rejectsAs400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"writePolicy": "project"}
                """)
            .when().patch("/api/settings")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_writePolicyProjectWithArchivedDefaultScope_rejectsAs400() {
        Scope archived = new Scope();
        archived.id = UUID.randomUUID();
        archived.slug = "alpha";
        archived.kind = ScopeKind.PROJECT;
        archived.archived = true;
        when(scopes.requireBySlug("alpha")).thenReturn(archived);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"writePolicy": "project", "defaultScopeSlug": "alpha"}
                """)
            .when().patch("/api/settings")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_writePolicyProjectWithPrivateDefaultScope_rejectsAs400() {
        // The default scope must be a PROJECT — private/global can't be elected.
        Scope priv = new Scope();
        priv.id = UUID.randomUUID();
        priv.slug = "personal";
        priv.kind = ScopeKind.PRIVATE;
        priv.archived = false;
        when(scopes.requireBySlug("personal")).thenReturn(priv);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"writePolicy": "project", "defaultScopeSlug": "personal"}
                """)
            .when().patch("/api/settings")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_writePolicyProjectWithValidScope_setsDefaultScopeId() {
        UUID scopeId = UUID.randomUUID();
        Scope alpha = new Scope();
        alpha.id = scopeId;
        alpha.slug = "alpha";
        alpha.kind = ScopeKind.PROJECT;
        alpha.archived = false;
        when(scopes.requireBySlug("alpha")).thenReturn(alpha);

        // After mutation, the view re-reads settings.current() — same mock
        // instance, so the new writePolicy is observable in the response.
        when(resolver.resolve()).thenReturn(
            new Resolved(WritePolicy.PROJECT, WritePolicy.PROJECT, DefaultScopeStatus.OK, "alpha"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"writePolicy": "project", "defaultScopeSlug": "alpha"}
                """)
            .when().patch("/api/settings")
            .then()
                .statusCode(200)
                .body("writePolicy", equalTo("project"))
                .body("defaultScopeSlug", equalTo("alpha"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void update_writePolicyGlobal_clearsDefaultScopeId() {
        // Switching away from PROJECT clears defaultScopeId. The resolver returns
        // the new state.
        ts.defaultScopeId = UUID.randomUUID(); // pre-existing
        when(resolver.resolve()).thenReturn(
            new Resolved(WritePolicy.GLOBAL, WritePolicy.GLOBAL, DefaultScopeStatus.OK, null));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"writePolicy": "global"}
                """)
            .when().patch("/api/settings")
            .then()
                .statusCode(200)
                .body("writePolicy", equalTo("global"))
                .body("defaultScopeSlug", nullValue());
    }
}
