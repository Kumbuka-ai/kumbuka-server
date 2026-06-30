package ai.kumbuka.admin;

import ai.kumbuka.domain.GovernanceAudit;
import ai.kumbuka.mcp.ScopeLockTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * BUG-07 / dogfood-23 (D-CORE-17) — deterministic characterisation of the
 * scope-remap REST path end-to-end through a real HTTP dispatch and the
 * DevServices Postgres (move + governance-audit write, the way the team console
 * drives it). The dogfood-23 generic-error symptom was traced to F-0054 (a
 * missing INSERT grant on {@code governance_audit} on the prod tenant-backend
 * role, resolved at the bootstrap level via ADR-0026) — NOT a server-code or
 * MCP/REST-shape defect. These tests stand as the D-CORE-17 regression guard:
 * the happy-path move writes exactly one audit row, and a genuine rejection
 * surfaces as a TYPED 4xx code (never the generic retry toast).
 *
 * <p>Companion to {@link ScopeLockRestIT#admin_remap_lockedSource_marksOverride}
 * (the locked-source override leg). Seeds through the tenant-bound
 * {@link ScopeLockTestSupport}; the trust boundary is real.
 */
@QuarkusTest
@Tag("integration")
class ScopeRemapRestIT {

    @Inject ScopeLockTestSupport support;

    @AfterEach
    void cleanup() {
        support.cleanup();
    }

    // ---- plain admin remap, OPEN → OPEN, no collision → 200 + exactly one audit row ----
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void admin_remap_openToOpen_movesEntry_writesOneAudit() {
        UUID id = support.seedEntryThenLock("sl-remap-src", "remap-key", "move me");
        support.setLocked("sl-remap-src", false);   // dogfood-23 is a plain (unlocked) move
        support.ensureProject("sl-remap-dst", false);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("{\"targetScope\":\"sl-remap-dst\"}")
        .when().post("/api/scopes/sl-remap-src/entries/" + id + ":remap")
        .then().statusCode(200)
            .body("key", equalTo("remap-key"));

        // Lossless move: the row leaves the source and lands in the target.
        assertThat(support.entryCount("sl-remap-src")).isZero();
        assertThat(support.entryCount("sl-remap-dst")).isEqualTo(1);

        // Exactly one governance-audit row for this move (D-CORE-17), no override
        // marker (both scopes open).
        List<GovernanceAudit> remaps = support.auditRows("scope.remap").stream()
            .filter(a -> "sl-remap-dst".equals(a.payload.get("toScope")))
            .toList();
        assertThat(remaps).hasSize(1);
        assertThat(remaps.get(0).payload)
            .containsEntry("fromScope", "sl-remap-src")
            .containsEntry("override", false);
    }

    // ---- genuine rejection (target already holds the key) → TYPED 409 KEY_EXISTS ----
    // The generic retry toast (dogfood-23) must never stand in for a real reason.
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void admin_remap_keyCollisionInTarget_returns409_typedKeyExists() {
        UUID id = support.seedEntryThenLock("sl-remap-c-src", "dup-key", "src side");
        support.setLocked("sl-remap-c-src", false);
        support.seedEntryThenLock("sl-remap-c-dst", "dup-key", "dst side");
        support.setLocked("sl-remap-c-dst", false);

        given()
            .urlEncodingEnabled(false)
            .contentType(ContentType.JSON)
            .body("{\"targetScope\":\"sl-remap-c-dst\"}")
        .when().post("/api/scopes/sl-remap-c-src/entries/" + id + ":remap")
        .then().statusCode(409)
            .body("code", equalTo("KEY_EXISTS"));

        // Rejected move leaves both scopes untouched (atomic).
        assertThat(support.entryCount("sl-remap-c-src")).isEqualTo(1);
        assertThat(support.entryCount("sl-remap-c-dst")).isEqualTo(1);
    }
}
