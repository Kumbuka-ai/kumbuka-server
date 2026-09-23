package ai.kumbuka.erasure;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both internal erasure endpoints refuse every valid call, and they refuse
 * before anything changes.
 *
 * <h3>What is measured</h3>
 *
 * <p>Row counts across every table {@link MemberErasureService} and
 * {@link TenantDataPurgeService} write to, taken before the call and again
 * after it, plus the value of the tombstone column itself. The counts are the
 * statement; the 503 is only how the endpoint announces it. That is why the
 * counts are asserted first: an endpoint that refused <em>after</em> running
 * its service would still answer 503, and a probe that stopped at the status
 * would pass it.
 *
 * <p>Two tenants' worth of cases, both against the V1–V25 chain:
 * <ul>
 *   <li><b>with rows in {@code public.memory}</b> — the case the incomplete
 *       erasure path is most visibly about, and the one where the purge would
 *       run into {@code memory_scope_id_fkey}'s {@code ON DELETE RESTRICT};</li>
 *   <li><b>without them</b> — the discriminating case, where the core's own
 *       share would otherwise run to completion and nothing but the refusal
 *       stands between the caller and a deletion this service cannot finish.</li>
 * </ul>
 *
 * <h3>Why the counting role is not the datasource role</h3>
 *
 * <p>The effect of a call runs under whatever role the application's
 * datasource connects as — in this substrate the DevServices account, which
 * is a superuser and so bypasses RLS. The <em>counting</em> is what this
 * probe can hold to the core's runtime shape, so it runs under
 * {@link #RUNTIME_ROLE}: {@code NOSUPERUSER}, {@code NOBYPASSRLS}, reading
 * through the same {@code app.tenant_id} GUC the application sets. A count
 * taken that way cannot see more than the core sees.
 *
 * <h3>Why this probe pins its own tenant</h3>
 *
 * <p>The endpoints refuse any tenant but the resolver's, and in this edition
 * the resolver answers with {@code kumbuka.tenant-id}. A valid call is
 * therefore only possible against the configured tenant, and driving one
 * against the V1 singleton would empty the database every other integration
 * test shares.
 *
 * <p>Every expectation here comes from the commission and from the counts
 * taken before the call — never from the endpoint's own answer.
 */
@QuarkusTest
@TestProfile(ErasureEndpointsRefuseIT.PinnedTenant.class)
@Tag("integration")
class ErasureEndpointsRefuseIT {

    /** This probe's own tenant, away from the V1 singleton the other suites use. */
    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000e1e1e1e");

    public static class PinnedTenant implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.tenant-id", TENANT.toString());
        }
    }

    /** Matches kumbuka.internal.erasure.token in test/resources/application.properties. */
    private static final String TOKEN = "test-erase-token";

    /** The member this fixture plants, and whose subject the probe scope carries. */
    private static final String SUBJECT = "erasure-probe-subject";

    private static final String ERASE_PATH = "/api/internal/erase-subject";
    private static final String PURGE_PATH = "/api/internal/purge-tenant";

    /**
     * Stand-in for the core's runtime role (V25 names it {@code kumbuka}).
     * Created here because the migration chain deliberately does not create
     * it — V25 reads it out of the catalogue rather than issuing a CREATE.
     */
    private static final String RUNTIME_ROLE = "core_runtime_probe";

    @Inject AgroalDataSource dataSource;

    /**
     * Row counts across every table the two erasure services write to, plus
     * the tombstone column itself. Authorship is carried as values, not as a
     * count: the member erasure rewrites {@code scope.created_by} in place,
     * so a probe that only counted rows would stay green straight through it.
     */
    private record Snapshot(
        long userAccounts,
        long teamSettings,
        long scopes,
        long scopeStats,
        long teams,
        long memoryRows,
        List<String> scopeAuthorship) {}

    // =======================================================================
    // Fixture
    // =======================================================================

    @BeforeEach
    void plantTenant() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(
                    "DO $$ BEGIN "
                  + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + RUNTIME_ROLE + "') THEN"
                  + "    CREATE ROLE " + RUNTIME_ROLE + " NOSUPERUSER NOBYPASSRLS NOINHERIT;"
                  + "  END IF; "
                  + "END $$;");
                s.execute("GRANT USAGE ON SCHEMA public, platform TO " + RUNTIME_ROLE);
                s.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + RUNTIME_ROLE);
                s.execute("GRANT SELECT ON ALL TABLES IN SCHEMA platform TO " + RUNTIME_ROLE);

                s.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', false)");
                s.execute("INSERT INTO platform.team (id, tenant_id, name, alias) VALUES "
                    + "('" + TENANT + "', '" + TENANT + "', 'Erasure probe', 'erasure-probe') "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO platform.scope (tenant_id, slug, name, kind, fixed, created_by) VALUES "
                    + "('" + TENANT + "', 'global', 'global', 'global', true, NULL), "
                    + "('" + TENANT + "', 'private', 'private', 'private', false, NULL), "
                    + "('" + TENANT + "', 'probe', 'probe', 'project', false, '" + SUBJECT + "') "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO platform.team_settings (tenant_id) VALUES ('" + TENANT + "') "
                    + "ON CONFLICT (tenant_id) DO NOTHING");
                s.execute("INSERT INTO platform.user_account "
                    + "(tenant_id, subject, email, role, status) VALUES "
                    + "('" + TENANT + "', '" + SUBJECT + "', 'probe@example.invalid', 'member', 'active') "
                    + "ON CONFLICT DO NOTHING");
            }
            c.commit();
        }
    }

    /**
     * Plant one entry for this tenant. Called only by the cases that speak
     * for a tenant which still holds content — the fixture starts empty so
     * the discriminating case is the default rather than an afterthought.
     */
    private void plantOneEntry() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', false)");
                s.execute("INSERT INTO public.memory "
                    + "(tenant_id, owner_subject, scope_id, type, content, logical_id, is_private) "
                    + "SELECT '" + TENANT + "', '" + SUBJECT + "', sc.id, 'decision', 'probe content', "
                    + "       gen_random_uuid(), false "
                    + "  FROM platform.scope sc "
                    + " WHERE sc.tenant_id = '" + TENANT + "' AND sc.slug = 'probe'");
            }
            c.commit();
        }
    }

    /**
     * Remove everything this fixture planted. Runs whatever the endpoint did,
     * so a probe that measured a real deletion leaves the shared database in
     * the same state as one that measured a refusal.
     */
    @AfterEach
    void unplantTenant() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', false)");
                s.execute("DELETE FROM public.memory WHERE tenant_id = '" + TENANT + "'");
                s.execute("DELETE FROM platform.team_settings WHERE tenant_id = '" + TENANT + "'");
                s.execute("DELETE FROM platform.user_account WHERE tenant_id = '" + TENANT + "'");
                s.execute("DELETE FROM platform.scope WHERE tenant_id = '" + TENANT + "'");
                s.execute("DELETE FROM platform.team WHERE tenant_id = '" + TENANT + "'");
            }
            c.commit();
        }
    }

    // =======================================================================
    // The four cases
    // =======================================================================

    @Test
    void eraseSubjectRefuses_forATenantThatHoldsNoEntries() throws SQLException {
        assertRefusalChangedNothing(ERASE_PATH, eraseBody(),
            ErasurePathIncomplete.ERASE_MESSAGE, "erase-subject, tenant without entries");
    }

    @Test
    void eraseSubjectRefuses_forATenantThatStillHoldsEntries() throws SQLException {
        plantOneEntry();
        assertRefusalChangedNothing(ERASE_PATH, eraseBody(),
            ErasurePathIncomplete.ERASE_MESSAGE, "erase-subject, tenant with entries");
    }

    @Test
    void purgeTenantRefuses_forATenantThatHoldsNoEntries() throws SQLException {
        assertRefusalChangedNothing(PURGE_PATH, purgeBody(),
            ErasurePathIncomplete.PURGE_MESSAGE, "purge-tenant, tenant without entries");
    }

    @Test
    void purgeTenantRefuses_forATenantThatStillHoldsEntries() throws SQLException {
        plantOneEntry();
        assertRefusalChangedNothing(PURGE_PATH, purgeBody(),
            ErasurePathIncomplete.PURGE_MESSAGE, "purge-tenant, tenant with entries");
    }

    /**
     * The refusals that were there before this one still answer, and still
     * answer first. Order is the point: a caller who does not hold the shared
     * secret must learn nothing new from the incomplete erasure path, so the
     * 401 has to come before the 503. The remaining older refusal — the 503
     * of an unconfigured host — cannot be reached from this profile and is
     * measured in {@link ErasureUnconfiguredRefusalIT}.
     */
    @Test
    void theOlderRefusalsStillAnswerFirst() {
        final UUID foreignTenant = UUID.fromString("11111111-1111-1111-1111-111111111111");

        for (String path : new String[] { ERASE_PATH, PURGE_PATH }) {
            final boolean isErase = path.equals(ERASE_PATH);
            final String validBody = isErase ? eraseBody() : purgeBody();

            assertThat(post(path, null, validBody).statusCode())
                .as("%s without the shared secret must still answer 401, not the "
                    + "erasure-path 503 — the refusal may not become an oracle", path)
                .isEqualTo(401);

            assertThat(post(path, "Bearer wrong-token", validBody).statusCode())
                .as("%s with a wrong shared secret must still answer 401", path)
                .isEqualTo(401);

            final Response incomplete = post(path, "Bearer " + TOKEN, "{}");
            assertThat(incomplete.statusCode())
                .as("%s with an incomplete body must still answer 400. Body: %s",
                    path, incomplete.asString())
                .isEqualTo(400);
            assertThat(incomplete.jsonPath().getString("error")).isEqualTo("bad_request");

            final String misroutedBody = isErase
                ? "{\"tenantId\":\"" + foreignTenant + "\",\"subject\":\"somebody\"}"
                : "{\"tenantId\":\"" + foreignTenant + "\"}";
            final Response misrouted = post(path, "Bearer " + TOKEN, misroutedBody);
            assertThat(misrouted.statusCode())
                .as("%s for a foreign tenant must still answer 400. Body: %s",
                    path, misrouted.asString())
                .isEqualTo(400);
            assertThat(misrouted.jsonPath().getString("error")).isEqualTo("tenant_mismatch");
        }
    }

    // =======================================================================
    // The measurement
    // =======================================================================

    /**
     * Drive one endpoint and hold it to both halves of the commission: it
     * changed nothing, and it said so in the commissioned envelope.
     */
    private void assertRefusalChangedNothing(
            String path, String body, String expectedMessage, String label) throws SQLException {

        final Snapshot before = snapshot();

        final Response answer = post(path, "Bearer " + TOKEN, body);

        final Snapshot after = snapshot();

        assertThat(after.scopeAuthorship())
            .as("%s left the scope-provenance tombstone alone. Before: %s, after: %s",
                label, before.scopeAuthorship(), after.scopeAuthorship())
            .isEqualTo(before.scopeAuthorship());

        assertThat(after)
            .as("%s changed a row count. Before: %s, after: %s", label, before, after)
            .isEqualTo(before);

        assertThat(answer.statusCode())
            .as("%s must refuse with 503 while the erasure path across the service "
                + "boundary is missing — a 2xx asserts a deletion that did not happen. "
                + "Body: %s", label, answer.asString())
            .isEqualTo(503);

        assertThat(answer.jsonPath().getString("error"))
            .as("%s carries the erasure-path error identifier", label)
            .isEqualTo(ErasurePathIncomplete.ERROR);

        assertThat(answer.jsonPath().getString("message"))
            .as("%s carries the commissioned message, word for word", label)
            .isEqualTo(expectedMessage);

        assertThat(answer.jsonPath().getMap("$").keySet())
            .as("%s answers in the endpoints' own two-field envelope and carries no "
                + "count: a number here would be read as an erasure that happened", label)
            .containsExactlyInAnyOrder("error", "message");
    }

    /** Count every table the erasure path writes to, under the runtime role and the tenant GUC. */
    private Snapshot snapshot() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', false)");
                s.execute("SET LOCAL SESSION AUTHORIZATION " + RUNTIME_ROLE);
                final Snapshot snap = new Snapshot(
                    count(s, "platform.user_account"),
                    count(s, "platform.team_settings"),
                    count(s, "platform.scope"),
                    count(s, "platform.scope_stats"),
                    count(s, "platform.team"),
                    count(s, "public.memory"),
                    authorship(s));
                c.rollback();
                return snap;
            }
        }
    }

    private static long count(Statement s, String table) throws SQLException {
        try (ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = '" + TENANT + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The {@code created_by} values of the tenant's scopes, by slug, in a stable order. */
    private static List<String> authorship(Statement s) throws SQLException {
        final List<String> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(
                "SELECT slug, coalesce(created_by, '<null>') FROM platform.scope"
              + " WHERE tenant_id = '" + TENANT + "' ORDER BY slug")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "=" + rs.getString(2));
            }
        }
        return out;
    }

    private static Response post(String path, String authHeader, String body) {
        var request = given().contentType(ContentType.JSON).body(body);
        if (authHeader != null) {
            request = request.header("Authorization", authHeader);
        }
        return request.when().post(path).andReturn();
    }

    private static String eraseBody() {
        return "{\"tenantId\":\"" + TENANT + "\",\"subject\":\"" + SUBJECT + "\"}";
    }

    private static String purgeBody() {
        return "{\"tenantId\":\"" + TENANT + "\"}";
    }
}
