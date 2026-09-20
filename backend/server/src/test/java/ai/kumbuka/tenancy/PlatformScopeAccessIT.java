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
 * <p>Since V23 the base tables live in the schema {@code platform}, not in
 * {@code public}. The statements that name a schema explicitly say
 * {@code platform.} accordingly; the ones left unqualified stay unqualified on
 * purpose — they resolve through the connection's search_path, which is the
 * mechanism production actually relies on (the path sits on the database role),
 * so leaving them qualified would stop exercising it.
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
    /** V21 created it as {@code kumbuka_logbook}; V24 renames it to the service
     *  it actually belongs to, so that a later {@code log://} service cannot be
     *  confused with the dispatch one. */
    private static final String DISPATCH   = "kumbuka_dispatch";   // V21 + V24
    private static final String MEMORY     = "kumbuka_memory";     // V22, LOGIN, not BYPASSRLS
    private static final String OPS_READER = "kumbuka_ops_reader"; // V6, LOGIN BYPASSRLS
    /** V24. NOLOGIN, NOINHERIT, not BYPASSRLS — it exists to own one function. */
    private static final String RESOLVER   = "kumbuka_alias_resolver";
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

    /** `slug=kind` per row — V24's first new column, read back as the view answers it. */
    private List<String> readKinds(Statement s) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(
                 "SELECT slug, kind FROM platform.scope_access ORDER BY slug")) {
            while (rs.next()) out.add(rs.getString("slug") + "=" + rs.getString("kind"));
        }
        return out;
    }

    /** The three booleans of one row, as one string — so a wrong one names itself. */
    private String readFlags(Statement s, String slug) throws SQLException {
        try (ResultSet rs = s.executeQuery(
                 "SELECT locked, archived, can_write FROM platform.scope_access "
               + "WHERE slug = '" + slug + "'")) {
            if (!rs.next()) return "<no row>";
            return "locked=" + rs.getBoolean("locked")
                 + ",archived=" + rs.getBoolean("archived")
                 + ",can_write=" + rs.getBoolean("can_write");
        }
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
                    assertDenied(s, "SELECT 1 FROM platform." + t + " LIMIT 1");
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
               + "WHERE rolname IN ('" + WORKLIST + "','" + DISPATCH + "','" + MEMORY + "') "
               + "ORDER BY rolname")) {
            int n = 0;
            while (rs.next()) {
                n++;
                assertThat(rs.getBoolean("rolsuper")).as("%s rolsuper", rs.getString("rolname")).isFalse();
                assertThat(rs.getBoolean("rolbypassrls")).as("%s rolbypassrls", rs.getString("rolname")).isFalse();
            }
            assertThat(n)
                .as("all three enumerated readers present: the two steering roles (V21, the "
                    + "second of them renamed by V24) and the memory service (V22). The count "
                    + "is asserted, not just the attributes of whatever happened to be found — "
                    + "a role missing from the chain would otherwise pass this test by being "
                    + "absent from it")
                .isEqualTo(3);
        }
    }

    // ---- V24: the role carries the dispatch service's own name -------------

    /**
     * V21 named the dispatch service's role {@code kumbuka_logbook}, after a
     * service that has since been renamed. V24 renames the role to match, so a
     * later {@code log://} service cannot inherit the confusion.
     *
     * <p>A rename rather than a create-and-drop, because privileges hang off the
     * role's OID and travel with it — and because a role that still has a
     * password to lose must keep it. Both halves are asserted: the old name is
     * gone, the new one holds the grants V21 and V22 issued.
     */
    @Test
    void v24_theDispatchRoleIsNamedAfterItsService() throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM pg_roles WHERE rolname = 'kumbuka_logbook'")) {
                rs.next();
                assertThat(rs.getLong(1))
                    .as("V24 renamed kumbuka_logbook away; a database still carrying it "
                        + "either never ran V24 or hit the collision branch, and in the "
                        + "latter case kumbuka_dispatch is the granted one either way")
                    .isZero();
            }
            try (ResultSet rs = s.executeQuery(
                     "SELECT has_schema_privilege('" + DISPATCH + "','platform','USAGE') AS u,"
                   + "       has_table_privilege('" + DISPATCH + "','platform.scope_access','SELECT') AS v")) {
                rs.next();
                assertThat(rs.getBoolean("u")).as("USAGE on platform").isTrue();
                assertThat(rs.getBoolean("v")).as("SELECT on the view").isTrue();
            }
        }
    }

    // ---- V24: the alias resolution is part of the contract -----------------

    /**
     * The alias lookup a service needs BEFORE it has a tenant to bind — the
     * chicken-and-egg the platform's tenancy rule creates by ruling the tenant
     * id out of the token and leaving only the alias.
     *
     * <p>The subtlety this test exists for is that the lookup reads
     * {@code platform.team}, which is under FORCE ROW LEVEL SECURITY with a
     * policy keyed on {@code app.tenant_id} — the very thing that is not known
     * yet. SECURITY DEFINER alone does not get past that: a definer function
     * reads with its owner's privileges, and the base-table owner is bound by
     * the same policy. Measured on a database at V24: with the owner bound and
     * no tenant set, a KNOWN alias answers NULL, indistinguishable from an
     * unknown one, with nothing raised. So V24 owns the function with a role of
     * its own and admits that role through one policy addressed to it.
     *
     * <p>Both halves are asserted here, because only the pair is the claim: the
     * lookup answers, AND nothing else got wider — a service role still cannot
     * read {@code platform.team}, and the resolver can neither log in nor be
     * assumed with SET ROLE.
     */
    @Test
    void v24_aliasResolutionAnswersForEveryService_andWidensNothingElse() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                s.execute("INSERT INTO team (tenant_id, name, alias) VALUES "
                        + "('" + TENANT_A + "','Alpha','alpha-it')");

                for (String role : List.of(WORKLIST, DISPATCH, MEMORY)) {
                    s.execute("SAVEPOINT sp");
                    s.execute("SET LOCAL SESSION AUTHORIZATION " + role);
                    try (ResultSet rs = s.executeQuery(
                             "SELECT platform.tenant_id_by_alias('alpha-it')::text AS known,"
                           + "       platform.tenant_id_by_alias('no-such-alias')::text AS unknown")) {
                        rs.next();
                        assertThat(rs.getString("known"))
                            .as("%s resolves a known alias — a NULL here is the silent "
                                + "failure the resolver role exists to prevent", role)
                            .isEqualTo(TENANT_A);
                        assertThat(rs.getString("unknown"))
                            .as("%s gets NULL for an unknown alias, and learns nothing else", role)
                            .isNull();
                    }
                    // The wall is unchanged: the lookup is the whole widening.
                    // The refusal aborts the transaction, so the rollback to the
                    // savepoint has to be the next statement — it restores both
                    // the transaction and the SET LOCAL authorization.
                    assertDenied(s, "SELECT 1 FROM platform.team LIMIT 1");
                    s.execute("ROLLBACK TO SAVEPOINT sp");
                }
            }
            c.rollback();
        }
    }

    /** The resolver role is a function owner and nothing else. */
    @Test
    void v24_theResolverRoleCannotBeUsedAsAnIdentity() throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT rolcanlogin, rolbypassrls, rolsuper, rolinherit "
               + "FROM pg_roles WHERE rolname = '" + RESOLVER + "'")) {
            assertThat(rs.next()).as("V24 created %s", RESOLVER).isTrue();
            assertThat(rs.getBoolean("rolcanlogin")).as("NOLOGIN").isFalse();
            assertThat(rs.getBoolean("rolbypassrls"))
                .as("NOT BYPASSRLS — the point is a policy that names it, not a role "
                    + "that ignores every policy")
                .isFalse();
            assertThat(rs.getBoolean("rolsuper")).as("not a superuser").isFalse();
            assertThat(rs.getBoolean("rolinherit")).as("NOINHERIT").isFalse();
        }
    }

    /** The policy V24 adds is addressed to one role and reaches no other. */
    @Test
    void v24_theAliasPolicyIsAddressedToTheResolverAlone() throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT policyname, roles::text AS roles FROM pg_policies "
               + "WHERE schemaname='platform' AND tablename='team' ORDER BY policyname")) {
            List<String> seen = new ArrayList<>();
            while (rs.next()) seen.add(rs.getString("policyname") + "=" + rs.getString("roles"));
            assertThat(seen)
                .as("V3's isolation policy is untouched and applies to everyone; V24's "
                    + "applies to the resolver and to nobody else. A second entry naming "
                    + "{public} here would be a hole in the tenant boundary")
                .containsExactly(
                    "team_alias_resolution={" + RESOLVER + "}",
                    "team_tenant_isolation={public}");
        }
    }

    // ---- V24: kind, lock and the derived write right -----------------------

    /**
     * The three columns V24 appends, and the two answers V21 could not give.
     *
     * <p>{@code can_write} is derived from {@link ai.kumbuka.service.MemberWritePolicy}
     * as the specification: a muted member loses SHARED writes but keeps their
     * private scope, and a locked scope refuses every service-channel write. The
     * console's team-admin override is NOT representable here — it keys on the
     * channel, and a channel is not a row — so this column answers for a service
     * channel and the console keeps deriving its own override from {@code locked}.
     */
    @Test
    void v24_theViewPublishesKindLockAndTheDerivedWriteRight() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                // The kinds V21 filtered out, now addressable. One private and one
                // global per tenant is all the schema allows (V1's partial unique
                // indexes), so tenant A gets exactly one of each.
                s.execute("INSERT INTO scope (tenant_id, name, slug, kind, locked) VALUES "
                        + "('" + TENANT_A + "','A Private','private','private',false),"
                        + "('" + TENANT_A + "','A Global','global','global',false),"
                        + "('" + TENANT_A + "','A Locked','a-locked','project',true)");
                s.execute("SET LOCAL SESSION AUTHORIZATION " + DISPATCH);
                setGucs(s, TENANT_A, SUBJECT_A);

                assertThat(slugs(s))
                    .as("all three kinds are addressable now; V21 answered only for 'project'")
                    .containsExactly("a-locked", "a-project-one", "a-project-two", "global", "private");

                assertThat(readKinds(s))
                    .as("the kind is published, so a service can tell them apart")
                    .containsExactly("a-locked=project", "a-project-one=project",
                                     "a-project-two=project", "global=global", "private=private");

                assertThat(readFlags(s, "a-locked"))
                    .as("a locked project: published as locked, and not writable")
                    .isEqualTo("locked=true,archived=false,can_write=false");
                assertThat(readFlags(s, "a-project-one"))
                    .as("an open project for an unmuted member: writable")
                    .isEqualTo("locked=false,archived=false,can_write=true");

                // muted: shared writes go, the private scope stays. The mute is
                // set back under the migrator's authority — a service role holds
                // no privilege on user_account, which is the point of criterion
                // 4 and exactly what this suite must not quietly undo.
                s.execute("RESET SESSION AUTHORIZATION");
                s.execute("UPDATE user_account SET muted = true WHERE subject = '" + SUBJECT_A + "'");
                s.execute("SET LOCAL SESSION AUTHORIZATION " + DISPATCH);
                setGucs(s, TENANT_A, SUBJECT_A);
                assertThat(readFlags(s, "a-project-one"))
                    .as("MemberWritePolicy.assertCanWriteShared: a muted member loses shared writes")
                    .isEqualTo("locked=false,archived=false,can_write=false");
                assertThat(readFlags(s, "global"))
                    .as("the global scope is shared too — the mute reaches it")
                    .isEqualTo("locked=false,archived=false,can_write=false");
                assertThat(readFlags(s, "private"))
                    .as("MemberWritePolicy calls assertCanWriteShared only for a non-private "
                        + "scope: a muted member keeps their own private scope")
                    .isEqualTo("locked=false,archived=false,can_write=true");
            }
            c.rollback();
        }
    }

    /**
     * A private scope with an author belongs to that author; the autorless
     * per-tenant one belongs to every active member.
     *
     * <p>The second half is not a concession, it is the measured state of the
     * schema: {@code uq_scope_one_private} (V1) permits ONE private scope per
     * tenant and {@code scope.created_by} is NULL on it, because privacy in this
     * product sits one level down, on {@code memory.owner_subject}. A contract
     * that demanded an author would answer nothing at all for the only private
     * scope that exists.
     */
    @Test
    void v24_anAuthoredPrivateScopeIsVisibleToItsAuthorAlone() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                // A second active member of A, so "someone else" is a real subject.
                s.execute("INSERT INTO user_account (tenant_id, subject, email, role, status) "
                        + "VALUES ('" + TENANT_A + "','other-a','other@example.test','member','active')");
                s.execute("INSERT INTO scope (tenant_id, name, slug, kind, created_by) "
                        + "VALUES ('" + TENANT_A + "','A Private','private','private','" + SUBJECT_A + "')");
                s.execute("SET LOCAL SESSION AUTHORIZATION " + WORKLIST);

                setGucs(s, TENANT_A, SUBJECT_A);
                assertThat(slugs(s)).as("the author sees it").contains("private");

                setGucs(s, TENANT_A, "other-a");
                assertThat(slugs(s))
                    .as("another active member of the same tenant does not")
                    .doesNotContain("private");
                assertThat(slugs(s))
                    .as("and still sees the shared scopes — the clause filters, it does not empty")
                    .containsExactly("a-project-one", "a-project-two");
            }
            c.rollback();
        }
    }

    /** Red probe: without the author clause, a private scope leaks to a peer. */
    @Test
    void v24_probe_removingTheAuthorClauseLeaksAPrivateScope() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                setupOwnerShapeAndSeed(s);
                s.execute("INSERT INTO user_account (tenant_id, subject, email, role, status) "
                        + "VALUES ('" + TENANT_A + "','other-a','other@example.test','member','active')");
                s.execute("INSERT INTO scope (tenant_id, name, slug, kind, created_by) "
                        + "VALUES ('" + TENANT_A + "','A Private','private','private','" + SUBJECT_A + "')");
                // Break: the same view WITHOUT the author clause.
                s.execute("CREATE OR REPLACE VIEW platform.scope_access AS "
                        + "SELECT s.id AS scope_id, s.tenant_id AS tenant_id, s.slug AS slug, "
                        + "       s.archived AS archived, s.kind AS kind, s.locked AS locked, "
                        + "       (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write "
                        + "FROM scope s JOIN user_account ua ON ua.tenant_id = s.tenant_id "
                        + "WHERE s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid "
                        + "  AND ua.subject  = NULLIF(current_setting('app.subject', true), '') "
                        + "  AND ua.status   = 'active'");
                s.execute("SET LOCAL SESSION AUTHORIZATION " + WORKLIST);
                setGucs(s, TENANT_A, "other-a");
                assertThat(slugs(s))
                    .as("without the clause a peer sees a private scope that is not theirs")
                    .contains("private");
            }
            c.rollback(); // restores the real view definition
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
                    assertDenied(s, "SELECT 1 FROM platform." + t + " LIMIT 1");
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
                // The three columns V24 appended are carried along unchanged —
                // `CREATE OR REPLACE VIEW` refuses to drop a column, so a probe
                // that still listed only V21's four would fail on the DDL and
                // never reach the claim it exists to make.
                s.execute("CREATE OR REPLACE VIEW platform.scope_access AS "
                        + "SELECT s.id AS scope_id, s.tenant_id AS tenant_id, s.slug AS slug, "
                        + "       s.archived AS archived, s.kind AS kind, s.locked AS locked, "
                        + "       (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write "
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
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM platform.scope")) {
                    assertThat(rs.next()).as("probe 2: worklist can now read platform.scope").isTrue();
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
