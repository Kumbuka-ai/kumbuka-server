package ai.kumbuka.ratelimit;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration contract of the internal tenant-limits endpoint against a
 * real Postgres (DevServices) with the V19 migration and its row-level
 * security in force:
 *
 * <ul>
 *   <li>auth ladder: wrong/missing bearer → 401 (the 503-unconfigured case
 *       lives in {@link TenantLimitsResourceUnconfiguredTest});</li>
 *   <li>tenant binding: a path tenant that is not the resolver-bound
 *       tenant → 400 misroute guard;</li>
 *   <li>PATCH sets an override, GET reflects it; clearing falls back to
 *       the deployment defaults; validation rejects a partial band;</li>
 *   <li>the limiter's pre-binding config read (direct JDBC, no
 *       {@code app.tenant_id} GUC, non-superuser role) sees the override —
 *       the V19 open-read policy — while an unbound write fails closed.</li>
 * </ul>
 */
@QuarkusTest
@Tag("integration")
class TenantLimitsResourceIT {

    /** Singleton tenant seeded by V1__init.sql (matches test config). */
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "test-limits-token";
    private static final String RLS_TEST_ROLE = "rls_test_user_limits";

    @Inject TenantLimitsProvider provider;
    @Inject AgroalDataSource dataSource;

    private static String limitsPath(UUID tenant) {
        return "/api/internal/tenants/" + tenant + "/limits";
    }

    private void clearOverride() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"write\":null,\"tenantWrite\":null}")
            .when().patch(limitsPath(TENANT))
            .then().statusCode(200);
        provider.invalidate(TENANT);
    }

    @Test
    void unauthorizedWithoutOrWithWrongBearer() {
        given()
            .when().get(limitsPath(TENANT))
            .then().statusCode(401).body("error", equalTo("unauthorized"));
        given()
            .header("Authorization", "Bearer wrong-token")
            .contentType(ContentType.JSON)
            .body("{\"write\":null,\"tenantWrite\":null}")
            .when().patch(limitsPath(TENANT))
            .then().statusCode(401);
    }

    @Test
    void misroutedTenantIsRefused() {
        UUID foreignTenant = UUID.fromString("00000000-0000-0000-0000-00000000beef");
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .when().get(limitsPath(foreignTenant))
            .then().statusCode(400).body("error", equalTo("tenant_mismatch"));
    }

    @Test
    void partialBandIsRejected() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"write\":{\"burstCapacity\":100},\"tenantWrite\":null}")
            .when().patch(limitsPath(TENANT))
            .then().statusCode(400).body("error", equalTo("invalid_band"));
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"write\":{\"burstCapacity\":100,\"refillTokens\":0,\"refillPeriodSeconds\":60},"
                + "\"tenantWrite\":null}")
            .when().patch(limitsPath(TENANT))
            .then().statusCode(400).body("error", equalTo("invalid_band"));
    }

    @Test
    void defaultsApplyWhenNoOverrideExists() {
        clearOverride();
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .when().get(limitsPath(TENANT))
            .then()
                .statusCode(200)
                .body("source", equalTo("default"))
                .body("override", nullValue())
                .body("tenantAggregate", nullValue())
                .body("effective.burstCapacity", equalTo(600))
                .body("effective.refillTokens", equalTo(120))
                .body("effective.refillPeriodSeconds", equalTo(60));
    }

    @Test
    void patchSetsOverrideGetReflectsItAndClearRestoresDefaults() {
        clearOverride();

        // Set: override + tenant-aggregate band.
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"write\":{\"burstCapacity\":50,\"refillTokens\":10,\"refillPeriodSeconds\":30},"
                + "\"tenantWrite\":{\"burstCapacity\":500,\"refillTokens\":100,\"refillPeriodSeconds\":60}}")
            .when().patch(limitsPath(TENANT))
            .then()
                .statusCode(200)
                .body("source", equalTo("override"))
                .body("effective.burstCapacity", equalTo(50));

        given()
            .header("Authorization", "Bearer " + TOKEN)
            .when().get(limitsPath(TENANT))
            .then()
                .statusCode(200)
                .body("source", equalTo("override"))
                .body("override.burstCapacity", equalTo(50))
                .body("override.refillTokens", equalTo(10))
                .body("override.refillPeriodSeconds", equalTo(30))
                .body("tenantAggregate.burstCapacity", equalTo(500))
                .body("defaults.burstCapacity", equalTo(600));

        // Runtime reconfigurability: the limiter's provider sees the new
        // band immediately (the PATCH invalidated the cache).
        EffectiveWriteRateLimits effective = provider.effectiveLimits(TENANT);
        assertThat(effective.principalBand()).isEqualTo(new WriteRateBand(50, 10, 30));
        assertThat(effective.tenantAggregateBand()).contains(new WriteRateBand(500, 100, 60));

        // Clear: back to the deployment defaults.
        clearOverride();
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .when().get(limitsPath(TENANT))
            .then()
                .statusCode(200)
                .body("source", equalTo("default"))
                .body("override", nullValue());
        assertThat(provider.effectiveLimits(TENANT).principalBand())
            .isEqualTo(new WriteRateBand(600, 120, 60));
    }

    /**
     * The V19 row-level-security shape, felt by a NON-superuser role (the
     * DevServices app account is a superuser and would bypass RLS):
     * SELECT works WITHOUT the {@code app.tenant_id} GUC — the limiter's
     * pre-binding read — while an unbound INSERT fails closed.
     */
    @Test
    void openReadPolicyServesThePreBindingLimiterRead() throws SQLException {
        clearOverride();
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"write\":{\"burstCapacity\":77,\"refillTokens\":7,\"refillPeriodSeconds\":60},"
                + "\"tenantWrite\":null}")
            .when().patch(limitsPath(TENANT))
            .then().statusCode(200);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(
                    "DO $$ BEGIN "
                  + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + RLS_TEST_ROLE + "') THEN"
                  + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS NOINHERIT;"
                  + "  END IF; "
                  + "END $$;");
                s.execute("GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE);
                s.execute("GRANT SELECT, INSERT ON tenant_limits TO " + RLS_TEST_ROLE);
                s.execute("SET LOCAL SESSION AUTHORIZATION " + RLS_TEST_ROLE);

                // No app.tenant_id GUC bound — the open-read policy must
                // still serve the row (this IS the limiter's read shape).
                try (ResultSet rs = s.executeQuery(
                        "SELECT write_burst_capacity FROM tenant_limits WHERE tenant_id = '" + TENANT + "'")) {
                    assertThat(rs.next())
                        .as("pre-binding read sees the override row without the GUC")
                        .isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(77);
                }

                // Writes stay tenant-bound: an unbound INSERT fails closed.
                assertThat(insertWithoutGucFails(s))
                    .as("INSERT without the tenant GUC must be refused by RLS")
                    .isTrue();
            } finally {
                c.rollback();
            }
        }

        clearOverride();
    }

    private static boolean insertWithoutGucFails(Statement s) {
        try {
            s.execute("INSERT INTO tenant_limits (tenant_id, write_burst_capacity, "
                + "write_refill_tokens, write_refill_period_seconds) "
                + "VALUES ('00000000-0000-0000-0000-00000000cafe', 10, 10, 60)");
            return false;
        } catch (SQLException expected) {
            return true;
        }
    }
}
