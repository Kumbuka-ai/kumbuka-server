package ai.kumbuka.admin;

import ai.kumbuka.domain.GovernanceAudit;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * the console (admin REST) surface of scope-lock,
 * end-to-end through a real HTTP dispatch so {@code @RolesAllowed} role gating,
 * the admin-only flip, the governance-audit events and the serialized lock flag
 * are all exercised the way the team console drives them.
 *
 * <p>What the lock <em>refuses</em> — a write into a locked scope — was
 * exercised here and on the MCP wire until the memory engine left the core.
 * Both sets of cases went with the write paths they drove; the policy that
 * decides the refusal ({@code MemberWritePolicy}) is untouched, because the
 * platform read contract V24 is derived from it and described against it.
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
