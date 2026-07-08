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
import java.util.List;

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
 * Flyway (V4__waitlist_intake.sql + V7__waitlist_utm_attribution.sql) is the
 * SOURCE OF TRUTH for this table; the DDL below is a TEST-ONLY MIRROR of it,
 * created in {@link #createOpsFixture()} so the intake endpoint has a target to
 * INSERT into. Keep it in sync with V4/V7 if those change (shape only —
 * id/status/timestamp defaults + the partial unique index on lower(email) WHERE
 * status &lt;&gt; 'rejected'; the five nullable UTM columns from V7).
 *
 * <h3>Grant caveat (proven elsewhere)</h3>
 *
 * This IT runs against DevServices as the DB OWNER, so it exercises the intake
 * LOGIC (sanitizing, truncation, null-handling, snake_case persistence) but NOT
 * the narrow app-role grant. That the shipped TABLE-LEVEL INSERT grant
 * (ops-console deploy/bootstrap/09-waitlist-grants.sql) actually covers the new
 * V7 columns is proven against real Postgres — under a role carrying only that
 * grant — in ops-console's {@code WaitlistUtmMigrationIT}.
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
            // --- BEGIN test mirror of ops-console V4__waitlist_intake.sql
            //     + V7__waitlist_utm_attribution.sql (the five nullable UTM
            //     columns the intake now persists). Keep in sync with those. ---
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
              + "  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),"
              + "  utm_source               TEXT,"
              + "  utm_medium               TEXT,"
              + "  utm_campaign             TEXT,"
              + "  utm_content              TEXT,"
              + "  referrer                 TEXT"
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
    void utmFields_persistedSnakeCase_truncatedAndBlanksNulled() throws SQLException {
        // Over-cap source (70 -> 64) and campaign (130 -> 128); a blank content
        // stored as NULL; medium and (origin-trimmed) referrer pass through.
        final String longSource = "s".repeat(70);
        final String longCampaign = "c".repeat(130);
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"utm@acme.io\",\"teamName\":\"Acme\","
                + "\"utmSource\":\"" + longSource + "\","
                + "\"utmMedium\":\"email\","
                + "\"utmCampaign\":\"" + longCampaign + "\","
                + "\"utmContent\":\"   \","
                + "\"referrer\":\"https://news.ycombinator.com\"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(200)
                .body("success", is(true));

        assertThat(selectString("utm@acme.io", "utm_source")).isEqualTo(longSource.substring(0, 64));
        assertThat(selectString("utm@acme.io", "utm_medium")).isEqualTo("email");
        assertThat(selectString("utm@acme.io", "utm_campaign")).isEqualTo(longCampaign.substring(0, 128));
        assertThat(selectString("utm@acme.io", "utm_content")).isNull();
        assertThat(selectString("utm@acme.io", "referrer")).isEqualTo("https://news.ycombinator.com");
    }

    @Test
    void noUtmFields_persistsNulls_backwardsCompat() throws SQLException {
        // Organic, no-UTM traffic: the POST still succeeds and every attribution
        // column stays NULL (the pre-D-OPS-32 contract must keep working).
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"organic@acme.io\",\"teamName\":\"Organic\"}")
            .when().post("/api/public/waitlist-intake")
            .then()
                .statusCode(200)
                .body("success", is(true));

        for (String col : List.of("utm_source", "utm_medium", "utm_campaign", "utm_content", "referrer")) {
            assertThat(selectString("organic@acme.io", col))
                .as("%s is NULL for organic traffic", col).isNull();
        }
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

    /** Read one column of the (single) row for {@code email}; {@code null} if SQL NULL. */
    private String selectString(String email, String column) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT " + column + " FROM ops.waitlist_entry WHERE email = '" + email + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
