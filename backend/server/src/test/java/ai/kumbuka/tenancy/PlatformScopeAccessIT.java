package ai.kumbuka.tenancy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Witnesses the tenancy-directory (V21 migration) acceptance criteria 1-6 and
 * red probes 1-3 of SATELLITE_143.1, per SATELLITE_143.2. Criterion 7 (the view
 * owner after the deploy-path sweep) lives in ops-console's cold-start replay,
 * not here.
 *
 * <p>DevServices runs Postgres with a SUPERUSER app account, so the production
 * owner shape does not exist here (the owner-normalisation sweep does not run in
 * tests). Each test therefore constructs it inside an uncommitted transaction —
 * a non-super, non-BYPASSRLS role owns the base tables and the view — exactly the
 * {@code rls_test_user} + {@code SET LOCAL SESSION AUTHORIZATION} idiom
 * {@link CrossTenantIsolationIT} uses. Everything is rolled back, so the shared
 * DevServices database is left untouched for other tests. With that shape,
 * criteria 2 and 3 exercise RLS THROUGH the view under {@code FORCE ROW LEVEL
 * SECURITY} — the measurement SATELLITE_143.1 could not take (measurement 3).
 */
@QuarkusTest
@Tag("integration")
class PlatformScopeAccessIT {

    @Inject AgroalDataSource ds;

    private static final String WORKLIST   = "kumbuka_worklist";   // V21, LOGIN, not BYPASSRLS
    private static final String LOGBOOK    = "kumbuka_logbook";    // V21, LOGIN, not BYPASSRLS
    private static final String MEMORY     = "kumbuka_memory";     // V22, LOGIN, not BYPASSRLS
    private static final String OPS_READER = "kumbuka_ops_reader"; // V6, LOGIN BYPASSRLS
    /** A kumbuka-like base-table owner: non-super, non-BYPASSRLS, as the deploy
     *  path's owner-normalisation leaves it. */
    private static final String OWNER = "tenancy_dir_owner_probe";

    private static final String TENANT_A  = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B  = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUBJECT_A = "subject-a-active";
    private static final String SUBJECT_B = "subject-b-active";

    // ---- helpers -----------------------------------------------------------

    /** As superuser, inside the caller's uncommitted tx: build the production
     *  owner shape (non-super OWNER owns the base tables + the view) and seed two
     *  tenants. Superuser inserts bypass RLS. */
    private void setupOwnerShapeAndSeed(Statement s) throws SQLException {
        s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + OWNER + "') "
                + "THEN CREATE ROLE " + OWNER + " NOSUPERUSER NOBYPASSRLS NOLOGIN; END IF; END $$;");
        s.execute("ALTER TABLE scope OWNER TO " + OWNER);
        s.execute("ALTER TABLE user_account OWNER TO " + OWNER);
        s.execute("ALTER VIEW platform.scope_access OWNER TO " + OWNER);
        s.execute("INSERT INTO user_account (tenant_id, subject, email, role, status) VALUES "
                + "('" + TENANT_A + "','" + SUBJECT_A + "','a@example.test','member','active'),"
                + "('" + TENANT_B + "','" + SUBJECT_B + "','b@example.test','member','active')");
        s.execute("INSERT INTO scope (tenant_id, name, slug, kind) VALUES "
                + "('" + TENANT_A + "','A Project One','a-project-one','project'),"
                + "('" + TENANT_A + "','A Project Two','a-project-two','project'),"
                + "('" + TENANT_B + "','B Project','b-project','project')");
    }

    /** The two transaction-local session settings the view reads. Empty string
     *  means "unbound" — NULLIF(x,'') yields NULL and the view fails closed. */
    private void setGucs(Statement s, String tenant, String subject) throws SQLException {
        s.execute("SELECT set_config('app.tenant_id', '" + (tenant == null ? "" : tenant) + "', true)");
        s.execute("SELECT set_config('app.subject', '" + (subject == null ? "" : subject) + "', true)");
    }

    private long count(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM platform.scope_access")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> slugs(Statement s) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("SELECT slug FROM platform.scope_access ORDER BY slug")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private void assertDenied(Statement s, String sql) {
        assertThatThrownBy(() -> s.executeQuery(sql))
            .as("expected SQLSTATE 42501 for: %s", sql)
            .isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    // ---- criteria 1–3: the view filter + RLS through the view --------------

    @Test
    void criteria1to3_viewFiltersOnTenantAndSubject_underProdOwnerShape() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                s.execute("SET LOCAL SESSION AUTHORIZATION " + WORKLIST);

                // Criterion 1: neither setting bound -> zero rows, not an error.
                setGucs(s, "", "");
                assertThat(count(s)).as("criterion 1: neither setting bound").isZero();
                // Criterion 1 (the self-filter guard): a bound tenant but an unset
                // subject must still be zero — the disguised-membership-list risk.
                setGucs(s, TENANT_A, "");
                assertThat(count(s)).as("criterion 1: tenant bound, subject unset").isZero();

                // Criterion 2: tenant A + an enabled member of A -> exactly A's
                // project scopes, no other tenant's row.
                setGucs(s, TENANT_A, SUBJECT_A);
                assertThat(slugs(s))
                    .as("criterion 2: A's project scopes for an active member of A")
                    .containsExactly("a-project-one", "a-project-two");

                // Criterion 3: tenant A bound, a subject of tenant B -> empty.
                setGucs(s, TENANT_A, SUBJECT_B);
                assertThat(count(s)).as("criterion 3: cross-subject is empty").isZero();
            }
            c.rollback();
        }
    }

    // ---- criterion 4: the base tables are unreachable for a steering role ---

    @Test
    void criterion4_worklistCannotReadTheBaseTables() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL SESSION AUTHORIZATION " + WORKLIST);
                for (String t : List.of("scope", "team", "user_account")) {
                    s.execute("SAVEPOINT sp");
                    assertDenied(s, "SELECT 1 FROM public." + t + " LIMIT 1");
                    s.execute("ROLLBACK TO SAVEPOINT sp");
                }
            }
            c.rollback();
        }
    }

    // ---- criterion 5: the ops reader is walled off from the view -----------

    @Test
    void criterion5_opsReaderCannotReadTheView() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL SESSION AUTHORIZATION " + OPS_READER);
                assertDenied(s, "SELECT 1 FROM platform.scope_access LIMIT 1");
            }
            c.rollback();
        }
    }

    // ---- criterion 6: the reader roles are non-super / non-BYPASSRLS --------

    @Test
    void criterion6_readerRolesAreNeitherSuperNorBypassrls() throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT rolname, rolsuper, rolbypassrls FROM pg_roles "
               + "WHERE rolname IN ('" + WORKLIST + "','" + LOGBOOK + "','" + MEMORY + "') "
               + "ORDER BY rolname")) {
            int n = 0;
            while (rs.next()) {
                n++;
                assertThat(rs.getBoolean("rolsuper")).as("%s rolsuper", rs.getString("rolname")).isFalse();
                assertThat(rs.getBoolean("rolbypassrls")).as("%s rolbypassrls", rs.getString("rolname")).isFalse();
            }
            assertThat(n)
                .as("all three enumerated readers present: the two steering roles (V21) and "
                    + "the memory service (V22). The count is asserted, not just the attributes "
                    + "of whatever happened to be found — a role missing from the chain would "
                    + "otherwise pass this test by being absent from it")
                .isEqualTo(3);
        }
    }

    // ---- the memory service: granted the view, walled off from the tables ---

    /**
     * V22's half of the arrangement, from this side of the line.
     *
     * <p>The memory engine is moving into a service of its own. Once it is out,
     * the scope it stores on every entry is another service's object, so the
     * reference becomes a runtime read of this view rather than a join — and
     * that read only works if this chain granted it. Nothing else in this suite
     * says so: V22 could be reverted, or never applied to a cluster, and every
     * other case here would stay green while the memory service failed to
     * resolve a single scope.
     *
     * <p>The second half is the more important one. The grant is on the VIEW and
     * on nothing else — no USAGE that reaches past it, no SELECT on the base
     * tables. That absence is what makes the contract a question the consumer
     * asks rather than a table it holds, and it is asserted here for the same
     * reason criterion 4 asserts it for the steering roles.
     */
    @Test
    void memoryService_readsTheDirectory_andIsWalledOffFromTheBaseTables() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                s.execute("SET LOCAL SESSION AUTHORIZATION " + MEMORY);

                // The grant: bound tenant and an active member of it, and the
                // directory answers — under the same FORCE-RLS owner shape the
                // steering roles are measured against.
                setGucs(s, TENANT_A, SUBJECT_A);
                assertThat(slugs(s))
                    .as("V22 grants the memory service SELECT on platform.scope_access; "
                        + "without it this read is refused with 42501 and the extracted "
                        + "service cannot resolve any scope at all")
                    .containsExactly("a-project-one", "a-project-two");

                // And the wall: the view is the whole of the entitlement.
                for (String t : List.of("scope", "team", "user_account")) {
                    s.execute("SAVEPOINT sp");
                    assertDenied(s, "SELECT 1 FROM public." + t + " LIMIT 1");
                    s.execute("ROLLBACK TO SAVEPOINT sp");
                }
            }
            c.rollback();
        }
    }

    // ---- red probe 1: the app.subject predicate is load-bearing ------------

    @Test
    void probe1_removingTheSubjectPredicateLeaks() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                // Break: the same view WITHOUT the `ua.subject = app.subject` line.
                s.execute("CREATE OR REPLACE VIEW platform.scope_access AS "
                        + "SELECT s.id AS scope_id, s.tenant_id AS tenant_id, s.slug AS slug, s.archived AS archived "
                        + "FROM scope s JOIN user_account ua ON ua.tenant_id = s.tenant_id "
                        + "WHERE s.kind = 'project' "
                        + "  AND s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid "
                        + "  AND ua.status = 'active'");
                s.execute("SET LOCAL SESSION AUTHORIZATION " + WORKLIST);
                // Tenant A bound, subject UNSET: with the predicate gone this leaks
                // A's scopes (criterion 1's guard goes red).
                setGucs(s, TENANT_A, "");
                assertThat(count(s))
                    .as("probe 1: without the app.subject predicate an unset subject leaks")
                    .isGreaterThan(0);
            }
            c.rollback(); // restores the real view definition
        }
    }

    // ---- red probe 2: the missing SELECT grant is the criterion-4 wall ------

    @Test
    void probe2_grantingScopeToWorklistBreaksCriterion4() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("GRANT SELECT ON scope TO " + WORKLIST); // break
                s.execute("SET LOCAL SESSION AUTHORIZATION " + WORKLIST);
                // The 42501 wall is gone — the select now succeeds.
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM public.scope")) {
                    assertThat(rs.next()).as("probe 2: worklist can now read public.scope").isTrue();
                }
            }
            c.rollback(); // revokes the grant
        }
    }

    // ---- red probe 3: the missing USAGE/SELECT is the criterion-5 wall ------

    @Test
    void probe3_grantingTheViewToOpsReaderBreaksCriterion5() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("GRANT USAGE ON SCHEMA platform TO " + OPS_READER);       // break
                s.execute("GRANT SELECT ON platform.scope_access TO " + OPS_READER); // break
                s.execute("SET LOCAL SESSION AUTHORIZATION " + OPS_READER);
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM platform.scope_access")) {
                    assertThat(rs.next()).as("probe 3: ops reader can now read the view").isTrue();
                }
            }
            c.rollback(); // revokes the grants
        }
    }
}
