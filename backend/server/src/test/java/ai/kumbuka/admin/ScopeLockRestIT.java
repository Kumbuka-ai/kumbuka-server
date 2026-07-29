package ai.kumbuka.admin;

import ai.kumbuka.domain.GovernanceAudit;
import ai.kumbuka.mcp.ScopeLockTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * the console (admin REST) surface of scope-lock,
 * end-to-end through a real HTTP dispatch so {@code @RolesAllowed} role gating,
 * the typed error bodies, the admin override path, and the governance-audit
 * events are all exercised the way the team console drives them. The MCP-wire
 * enforcement is in {@link ai.kumbuka.mcp.ScopeLockEnforcementIT}.
 *
 * <p>State is seeded through the tenant-bound {@link ScopeLockTestSupport}
 * (same single test tenant the request resolves) and audit rows are read back
 * through it — never through a mocked repo; the trust boundary is real.
 */
@QuarkusTest
@Tag("integration")
class ScopeLockRestIT {

    @Inject ScopeLockTestSupport support;

    @BeforeEach
    void seed() {
        support.ensureProject("sl-rest-locked", true);
        support.ensureProject("sl-rest-open", false);
        support.ensureProject("sl-rest-toggle", false);
    }

    @AfterEach
    void cleanup() {
        support.cleanup();
    }

    private List<GovernanceAudit> auditFor(String action, String scope) {
        return support.auditRows(action).stream()
            .filter(a -> scope.equals(a.payload.get("scope")))
            .toList();
    }

    // ---- 3. member console POST entry into a locked scope → 409 SCOPE_READ_ONLY ----
    @Test
    @TestSecurity(user = "m", roles = {"member"})
    void member_postEntry_lockedScope_returns409_scopeReadOnly() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"type\":\"decision\",\"key\":\"rest-rk1\",\"content\":\"c\"}")
            .when().post("/api/scopes/sl-rest-locked/entries")
            .then()
                .statusCode(409)
                .body("code", equalTo("SCOPE_READ_ONLY"));
    }

    // ---- 5. admin console create/update/delete on a locked scope → override + audit ----
    @Test
    @TestSecurity(user = "admin-crud", roles = {"admin"})
    void admin_consoleCrud_lockedScope_succeeds_emitsContentFreeOverrideAudit() {
        support.ensureProject("sl-rest-crud", true);

        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"type\":\"decision\",\"key\":\"crud-key\",\"content\":\"v1\"}")
            .when().post("/api/scopes/sl-rest-crud/entries")
            .then().statusCode(201)
            .extract().path("logicalId");

        given()
            .contentType(ContentType.JSON)
            .body("{\"type\":\"decision\",\"content\":\"v2 override\"}")
            .when().patch("/api/scopes/sl-rest-crud/entries/" + id)
            .then().statusCode(200);

        given()
            .when().delete("/api/scopes/sl-rest-crud/entries/" + id)
            .then().statusCode(204);

        List<GovernanceAudit> overrides = auditFor("entry.override", "sl-rest-crud");
        assertThat(overrides).hasSize(3);
        assertThat(overrides).extracting(a -> a.payload.get("operation"))
            .containsExactlyInAnyOrder("create", "update", "delete");
        // content-free: only the three identifier keys, actor = the admin sub
        assertThat(overrides).allSatisfy(a -> {
            assertThat(a.payload).containsOnlyKeys("scope", "entryId", "operation");
            assertThat(a.actorSubject).isEqualTo("admin-crud");
        });
    }

    // ---- admin console POST entry into a locked scope → 201 (override at REST) ----
    @Test
    @TestSecurity(user = "a", roles = {"admin"})
    void admin_postEntry_lockedScope_returns201_override() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"type\":\"decision\",\"key\":\"rest-ovr\",\"content\":\"override at rest\"}")
            .when().post("/api/scopes/sl-rest-locked/entries")
            .then()
                .statusCode(201);
    }

    // ---- 5b. admin remap touching a locked scope → override marker on scope.remap audit ----
    @Test
    @TestSecurity(user = "admin-remap", roles = {"admin"})
    void admin_remap_lockedSource_marksOverride() {
        UUID id = support.seedEntryThenLock("sl-rest-remap-src", "remap-k", "remap me");
        support.ensureProject("sl-rest-remap-dst", false);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("{\"targetScope\":\"sl-rest-remap-dst\"}")
            .when().post("/api/scopes/sl-rest-remap-src/entries/" + id + ":remap")
            .then().statusCode(200);

        List<GovernanceAudit> remaps = support.auditRows("scope.remap").stream()
            .filter(a -> id.toString().equals(a.payload.get("entryId")))
            .toList();
        assertThat(remaps).hasSize(1);
        assertThat(remaps.get(0).payload).containsEntry("override", true);
    }

    // ---- 9. axis composition: a SYSTEM entry in a locked scope stays blocked for admin ----
    @Test
    @TestSecurity(user = "admin-sys", roles = {"admin"})
    void systemEntry_inLockedScope_adminUpdate_stillBlocked_noOverrideAudit() {
        UUID sysId = support.seedSystemEntryThenLock("sl-rest-sys", "sys-key", "system seed");

        given()
            .contentType(ContentType.JSON)
            .body("{\"type\":\"decision\",\"content\":\"try override\"}")
            .when().patch("/api/scopes/sl-rest-sys/entries/" + sysId)
            .then()
                .statusCode(409)
                .body("code", equalTo("PROTECTED_UPDATE_BLOCKED"));

        // the entry-level lock threw before the override audit could fire
        assertThat(auditFor("entry.override", "sl-rest-sys")).isEmpty();
    }

    // ---- 6. member :lock → 403 (admin-only @RolesAllowed) ------------------
    @Test
    @TestSecurity(user = "m", roles = {"member"})
    void member_lock_returns403() {
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/sl-rest-open:lock")
            .then().statusCode(403);
    }

    // ---- 6. admin :lock then :unlock → 204 each; flag flips; both audited ---
    @Test
    @TestSecurity(user = "admin-tog", roles = {"admin"})
    void admin_lock_then_unlock_flipsFlag_andAuditsBothDirections() {
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/sl-rest-toggle:lock")
            .then().statusCode(204);
        assertThat(support.isLocked("sl-rest-toggle")).isTrue();

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/sl-rest-toggle:unlock")
            .then().statusCode(204);
        assertThat(support.isLocked("sl-rest-toggle")).isFalse();

        List<GovernanceAudit> locks = auditFor("scope.lock", "sl-rest-toggle");
        List<GovernanceAudit> unlocks = auditFor("scope.unlock", "sl-rest-toggle");
        assertThat(locks).hasSize(1);
        assertThat(unlocks).hasSize(1);
        assertThat(locks.get(0).payload).containsOnlyKeys("scope");
    }

    // ---- :lock on the private slug → 404 (admin code paths never reach private, P1) ----
    @Test
    @TestSecurity(user = "a", roles = {"admin"})
    void admin_lock_privateSlug_returns404() {
        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .when().post("/api/scopes/private:lock")
            .then().statusCode(404);
    }

    // ---- ScopeView.locked is serialized (drives the console lock icon) -----
    @Test
    @TestSecurity(user = "a", roles = {"admin"})
    void scopeListing_serializesLockedField() {
        given()
            .when().get("/api/scopes")
            .then()
                .statusCode(200)
                .body("find { it.slug == 'sl-rest-locked' }.locked", equalTo(true))
                .body("find { it.slug == 'sl-rest-open' }.locked", equalTo(false));
    }
}
