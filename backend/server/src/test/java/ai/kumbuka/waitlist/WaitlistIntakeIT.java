package ai.kumbuka.waitlist;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * REST-contract gate for {@code POST /api/public/waitlist-intake} (PROV-1a /
 * ADR-0015). Runs against a real Postgres (Quarkus DevServices container) so
 * the partial-unique-index 23505 → 409 path is actually exercised.
 *
 * <h3>Cross-schema test fixture</h3>
 *
 * kumbuka-server's Flyway only manages the {@code public} schema, so
 * {@code ops.waitlist_entry} does NOT exist in the test DB. The ops-console
 * Flyway (V4__waitlist_intake.sql) is the SOURCE OF TRUTH for this table; the
 * DDL below is a TEST-ONLY MIRROR of it, created in {@link #createOpsFixture()}
 * so the intake endpoint has a target to INSERT into. Keep it in sync with V4
 * if that ever changes (shape only — id/status/timestamp defaults + the partial
 * unique index on lower(email) WHERE status &lt;&gt; 'rejected').
 */
@QuarkusTest
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitlistIntakeIT {

    @Inject AgroalDataSource dataSource;

    /**
     * Create the {@code ops} schema + a mirror of ops-console V4's
     * {@code waitlist_entry} table. The real table is owned by the ops-console
     * Flyway in production; this is a standalone test fixture so kumbuka-server's
     * intake endpoint can be exercised in isolation.
     */
    @BeforeAll
    void createOpsFixture() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS ops");
            // --- BEGIN test mirror of ops-console V4__waitlist_intake.sql ---
            s.execute(
                "CREATE TABLE IF NOT EXISTS ops.waitlist_entry ("
              + "  id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),"
              + "  email                    TEXT        NOT NULL,"
              + "  team_name                TEXT        NOT NULL,"
              + "  contact                  TEXT,"
              + "  message                  TEXT,"
              + "  status                   TEXT        NOT NULL DEFAULT 'pending'"
              + "                                       CHECK (status IN ('pending','approved','rejected')),"
              + "  rejection_reason         TEXT,"
              + "  approved_tenant_id       UUID,"
              + "  approved_organization_id TEXT,"
              + "  created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),"
              + "  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()"
              + ")");
            s.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS waitlist_entry_email_active_uq "
              + "  ON ops.waitlist_entry (lower(email)) WHERE status <> 'rejected'");
            // --- END test mirror ---
        }
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE ops.waitlist_entry");
        }
    }

    @Test
    void happyPath_returns200_andPersistsRow() throws SQLException {
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"founder@acme.io\",\"teamName\":\"Acme\","
                + "\"contact\":\"Jane\",\"message\":\"please let us in\"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(200)
                .body("success", is(true))
                .body("id", notNullValue());

        assertThat(countByEmail("founder@acme.io")).isEqualTo(1L);
    }

    @Test
    void happyPath_optionalFieldsOmitted_returns200() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"solo@acme.io\",\"teamName\":\"Solo\"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(200)
                .body("success", is(true))
                .body("id", notNullValue());
    }

    @Test
    void invalidEmail_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"not-an-email\",\"teamName\":\"Acme\"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    void blankTeamName_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"founder@acme.io\",\"teamName\":\"   \"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    void duplicateActiveEmail_returns409() {
        // First intake lands a pending row.
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"dup@acme.io\",\"teamName\":\"Acme\"}")
            .when().post("/api/public/waitlist-intake")
            .then().statusCode(200);

        // Second intake for the same active email hits the partial-unique index.
        // Case-insensitive: the index is on lower(email).
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"DUP@acme.io\",\"teamName\":\"Acme Again\"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(409)
                .body("error", equalTo("already_registered"));
    }

    private long countByEmail(String email) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT COUNT(*) FROM ops.waitlist_entry WHERE email = '" + email + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
