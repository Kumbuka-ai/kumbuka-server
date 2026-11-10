// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.repo;

import ai.kumbuka.domain.TagStatus;
import ai.kumbuka.repo.TagRepository.TagAlreadyDeclaredException;
import ai.kumbuka.repo.TagRepository.TagEntry;
import ai.kumbuka.repo.TagRepository.TagNotFoundException;
import ai.kumbuka.repo.TagRepository.TagWriteDeniedException;
import ai.kumbuka.tenancy.TenantContext;
import ai.kumbuka.util.InvalidTagFieldException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tag vocabulary end to end: {@link TagRepository} as the composition calls
 * it, and {@code platform.tag_access} as the services read it.
 *
 * <p><b>Every case runs in one transaction that is rolled back</b>, so the shared
 * DevServices database is left untouched. DevServices hands out a SUPERUSER, which
 * row-level security never binds, so each case first builds the production owner
 * shape inside that transaction — a non-super, non-BYPASSRLS role owns
 * {@code scope}, {@code user_account}, {@code tag} and both views — and then
 * continues AS that role, the same idiom {@code PlatformScopeAccessIT} uses. The
 * repository therefore runs under {@code FORCE ROW LEVEL SECURITY} here, as the
 * core does in production.
 *
 * <p>A refusal from a {@code @Transactional} method marks the surrounding
 * transaction for rollback, so a case asserts at most one refusal and asserts it
 * last.
 */
@QuarkusTest
@org.junit.jupiter.api.Tag("integration")
class TagVocabularyIT {

    @Inject TagRepository tags;
    @Inject EntityManager em;
    @Inject UserTransaction tx;
    @Inject TenantContext tenantContext;
    @Inject AgroalDataSource ds;

    /** The V1 seed tenant, which the test profile binds by default. */
    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** A second tenant, seeded per case and rolled back with it. */
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** Base-table owner as the deployment leaves it: not super, not BYPASSRLS. */
    static final String OWNER = "tag_vocabulary_owner_probe";
    static final List<String> SERVICE_ROLES =
        List.of("kumbuka_worklist", "kumbuka_dispatch", "kumbuka_memory");

    static final String WRITER   = "tag-it-writer";
    static final String PEER     = "tag-it-peer";
    static final String MUTED    = "tag-it-muted";
    static final String B_MEMBER = "tag-it-b-member";

    static final String PROJECT   = "tag-it-project";
    static final String LOCKED    = "tag-it-locked";
    static final String B_PROJECT = "tag-it-b-project";

    // ---- declare and list ---------------------------------------------------

    @Test
    void declare_thenList_sortedByToken_withDescriptionAndStatus() throws Exception {
        inRolledBackTx(() -> {
            seed();
            TagEntry api = tags.declare(WRITER, PROJECT, "component:api", "the public API");
            assertThat(api.token()).isEqualTo("component:api");
            assertThat(api.status()).isEqualTo(TagStatus.DECLARED);
            assertThat(api.description()).isEqualTo("the public API");
            // Any member holding the write right may declare, not only the
            // scope's author.
            tags.declare(PEER, PROJECT, "alpha", null);
            tags.declare(WRITER, PROJECT, "component-x", null);

            assertThat(row("component:api"))
                .as("created_by is the caller; withdrawn_at stays empty")
                .isEqualTo("created_by=" + WRITER + ",status=declared,withdrawn_at=null");

            assertThat(tags.list(WRITER, PROJECT))
                .extracting(e -> e.token() + "|" + e.description() + "|" + e.status())
                .as("all tags of the scope, sorted by token in code-unit order")
                .containsExactly(
                    "alpha|null|DECLARED",
                    "component-x|null|DECLARED",
                    "component:api|the public API|DECLARED");
        });
    }

    // ---- withdraw -----------------------------------------------------------

    @Test
    void withdraw_setsStatusAndTimestamp_keepsTheRow_andRepeatingItIsANoop() throws Exception {
        inRolledBackTx(() -> {
            seed();
            tags.declare(WRITER, PROJECT, "alpha", "first");

            TagEntry withdrawn = tags.withdraw(WRITER, PROJECT, "alpha");
            assertThat(withdrawn.status()).isEqualTo(TagStatus.WITHDRAWN);
            String first = row("alpha");
            assertThat(first).startsWith("created_by=" + WRITER + ",status=withdrawn,withdrawn_at=")
                .doesNotEndWith("withdrawn_at=null");

            TagEntry again = tags.withdraw(PEER, PROJECT, "alpha");
            assertThat(again.status()).isEqualTo(TagStatus.WITHDRAWN);
            assertThat(row("alpha"))
                .as("a second withdrawal succeeds and changes nothing, not even the timestamp")
                .isEqualTo(first);

            assertThat(tags.list(WRITER, PROJECT))
                .extracting(e -> e.token() + "|" + e.description() + "|" + e.status())
                .as("a withdrawn tag is still listed, with its status")
                .containsExactly("alpha|first|WITHDRAWN");
        });
    }

    @Test
    void withdraw_ofAnUndeclaredToken_isNotFound() throws Exception {
        inRolledBackTx(() -> {
            seed();
            assertThatThrownBy(() -> tags.withdraw(WRITER, PROJECT, "never-declared"))
                .isInstanceOf(TagNotFoundException.class);
        });
    }

    // ---- double declaration -------------------------------------------------

    @Test
    void declaringADeclaredTokenAgain_isRefused_andNamesTheStatus() throws Exception {
        inRolledBackTx(() -> {
            seed();
            tags.declare(WRITER, PROJECT, "alpha", null);
            assertThatThrownBy(() -> tags.declare(PEER, PROJECT, "alpha", "again"))
                .isInstanceOfSatisfying(TagAlreadyDeclaredException.class,
                    e -> assertThat(e.status()).isEqualTo(TagStatus.DECLARED))
                .hasMessageContaining("status declared");
        });
    }

    @Test
    void declaringAWithdrawnTokenAgain_isRefused_andNamesTheStatus() throws Exception {
        inRolledBackTx(() -> {
            seed();
            tags.declare(WRITER, PROJECT, "alpha", null);
            tags.withdraw(WRITER, PROJECT, "alpha");
            assertThatThrownBy(() -> tags.declare(WRITER, PROJECT, "alpha", null))
                .isInstanceOfSatisfying(TagAlreadyDeclaredException.class,
                    e -> assertThat(e.status()).isEqualTo(TagStatus.WITHDRAWN))
                .hasMessageContaining("status withdrawn");
        });
    }

    @Test
    void theSameTokenInAnotherScopeIsADifferentTag() throws Exception {
        inRolledBackTx(() -> {
            seed();
            sql("RESET SESSION AUTHORIZATION");
            sql("UPDATE platform.scope SET locked = false WHERE slug = '" + LOCKED + "'");
            sql("SET LOCAL SESSION AUTHORIZATION " + OWNER);
            tags.declare(WRITER, PROJECT, "alpha", null);
            tags.declare(WRITER, LOCKED, "alpha", null);
            assertThat(tags.list(WRITER, LOCKED)).extracting(TagEntry::token).containsExactly("alpha");
        });
    }

    // ---- invalid input ------------------------------------------------------

    @Test
    void anInvalidToken_isRefusedWithTheFieldToken() throws Exception {
        inRolledBackTx(() -> {
            seed();
            assertThatThrownBy(() -> tags.declare(WRITER, PROJECT, "Not A Token", null))
                .isInstanceOfSatisfying(InvalidTagFieldException.class,
                    e -> assertThat(e.field()).isEqualTo("token"));
        });
    }

    @Test
    void anOverlongDescription_isRefusedWithTheFieldDescription() throws Exception {
        inRolledBackTx(() -> {
            seed();
            assertThatThrownBy(() -> tags.declare(WRITER, PROJECT, "alpha", "x".repeat(501)))
                .isInstanceOfSatisfying(InvalidTagFieldException.class,
                    e -> assertThat(e.field()).isEqualTo("description"));
        });
    }

    @Test
    void theTableRefusesAMalformedTokenEvenPastTheCore() throws Exception {
        inRolledBackTx(() -> {
            seed();
            // As the owner, straight into the table, tenant bound so the policy
            // admits the row: the CHECK is the backstop behind the core.
            sql("SELECT set_config('app.tenant_id', '" + TENANT_A + "', true)");
            assertThatThrownBy(() -> sql(
                    "INSERT INTO platform.tag (tenant_id, scope_id, token, created_by) "
                  + "SELECT tenant_id, id, 'Upper', 'x' FROM platform.scope WHERE slug = '" + PROJECT + "'"))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("tag_token_format");
        });
    }

    // ---- the write right ----------------------------------------------------

    @Test
    void declare_inALockedScope_isRefused() throws Exception {
        inRolledBackTx(() -> {
            seed();
            assertThatThrownBy(() -> tags.declare(WRITER, LOCKED, "alpha", null))
                .isInstanceOfSatisfying(TagWriteDeniedException.class,
                    e -> assertThat(e.scope()).isEqualTo(LOCKED));
        });
    }

    @Test
    void declare_byAMutedMember_isRefused() throws Exception {
        inRolledBackTx(() -> {
            seed();
            assertThatThrownBy(() -> tags.declare(MUTED, PROJECT, "alpha", null))
                .isInstanceOf(TagWriteDeniedException.class);
        });
    }

    @Test
    void withdraw_byAMutedMember_isRefused_andTheTagStays() throws Exception {
        inRolledBackTx(() -> {
            seed();
            tags.declare(WRITER, PROJECT, "alpha", null);
            assertThat(tags.list(MUTED, PROJECT))
                .as("a muted member still reads the vocabulary")
                .extracting(TagEntry::token).containsExactly("alpha");
            assertThatThrownBy(() -> tags.withdraw(MUTED, PROJECT, "alpha"))
                .isInstanceOf(TagWriteDeniedException.class);
        });
    }

    @Test
    void aScopeOfAnotherTenant_isNotFound() throws Exception {
        inRolledBackTx(() -> {
            seed();
            assertThatThrownBy(() -> tags.declare(WRITER, B_PROJECT, "alpha", null))
                .isInstanceOf(ScopeRepository.ScopeNotFoundException.class);
        });
    }

    // ---- tenant isolation, and its red probe --------------------------------

    /**
     * Tags of a second tenant and of a private scope the caller cannot see
     * appear neither in the list nor in the view.
     *
     * <p>Three foreign rows are planted, each held back by a different layer:
     * {@code b-own} sits in tenant B's scope (the join on {@code scope_access}),
     * {@code private-only} in tenant A's private scope authored by a peer (the
     * author clause of {@code scope_access}), and {@code b-stray} carries tenant
     * B but points at tenant A's project scope. The foreign key cannot refuse
     * that last row — it checks the scope exists, not whose it is — and the join
     * lets it through, because its scope IS visible. Only the tenant policy on
     * {@code platform.tag}, binding the view's owner under FORCE ROW LEVEL
     * SECURITY, keeps it out. That is the row the red probes below turn on.
     */
    @Test
    void tenantIsolation_foreignTagsAppearNeitherInTheListNorInTheView() throws Exception {
        inRolledBackTx(() -> {
            seed();
            declareTheVisibleTwo();
            assertTenantIsolation();
        });
    }

    /** Red probe, first half: the tenant predicate on {@code platform.tag} is load-bearing. */
    @Test
    void probe_withoutTheTenantPredicateOnTag_theTenantTestGoesRed() throws Exception {
        inRolledBackTx(() -> {
            seed("ALTER POLICY tag_tenant_isolation ON platform.tag USING (true) WITH CHECK (true)");
            declareTheVisibleTwo();
            assertThatThrownBy(this::assertTenantIsolation)
                .as("with the predicate gone, tenant B's stray row reaches tenant A's view")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("b-stray");
        });
    }

    /** Red probe, second half: FORCE is what binds the view's owner to that predicate. */
    @Test
    void probe_withoutForceRowLevelSecurityOnTag_theTenantTestGoesRed() throws Exception {
        inRolledBackTx(() -> {
            seed("ALTER TABLE platform.tag NO FORCE ROW LEVEL SECURITY");
            declareTheVisibleTwo();
            assertThatThrownBy(this::assertTenantIsolation)
                .as("without FORCE the owner is exempt from the policy, and so is the view")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("b-stray");
        });
    }

    // ---- the grants ---------------------------------------------------------

    /**
     * The three service roles read the vocabulary through the view and only
     * through it: SELECT on {@code platform.tag_access}, no privilege of any
     * kind on {@code platform.tag}.
     */
    @Test
    void theServiceRolesHoldSelectOnTheView_andNothingOnTheTable() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String role : SERVICE_ROLES) {
                    try (ResultSet rs = s.executeQuery(
                             "SELECT has_table_privilege('" + role + "','platform.tag_access','SELECT') AS v,"
                           + "       has_table_privilege('" + role + "','platform.tag',"
                           + "         'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AS t,"
                           + "       has_any_column_privilege('" + role + "','platform.tag',"
                           + "         'SELECT,INSERT,UPDATE,REFERENCES') AS c")) {
                        rs.next();
                        assertThat(rs.getBoolean("v")).as("%s: SELECT on platform.tag_access", role).isTrue();
                        assertThat(rs.getBoolean("t")).as("%s: any privilege on platform.tag", role).isFalse();
                        assertThat(rs.getBoolean("c")).as("%s: any column privilege on platform.tag", role).isFalse();
                    }
                    // And measured as the role, not only asked of the catalogue.
                    s.execute("SAVEPOINT sp");
                    s.execute("SET LOCAL SESSION AUTHORIZATION " + role);
                    assertThatThrownBy(() -> s.executeQuery("SELECT 1 FROM platform.tag LIMIT 1"))
                        .as("%s reading platform.tag", role)
                        .isInstanceOfSatisfying(SQLException.class,
                            e -> assertThat(e.getSQLState()).isEqualTo("42501"));
                    s.execute("ROLLBACK TO SAVEPOINT sp");
                    s.execute("SET LOCAL SESSION AUTHORIZATION " + role);
                    try (ResultSet rs = s.executeQuery("SELECT count(*) FROM platform.tag_access")) {
                        assertThat(rs.next()).as("%s reading platform.tag_access", role).isTrue();
                    }
                    s.execute("RESET SESSION AUTHORIZATION");
                }
            }
            c.rollback();
        }
    }

    // ---- helpers ------------------------------------------------------------

    @FunctionalInterface
    interface Body { void run() throws Exception; }

    private void inRolledBackTx(Body body) throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            tx.begin();
            try {
                body.run();
            } finally {
                tx.rollback();
            }
        }
    }

    /**
     * As the superuser, inside the case's transaction: the production owner
     * shape, two tenants' members and scopes, three foreign tags. Then each of
     * {@code breaks} (a red probe's damage), and then the switch to the owner,
     * which the rest of the case runs as.
     */
    private void seed(String... breaks) {
        sql("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + OWNER + "') "
          + "THEN CREATE ROLE " + OWNER + " NOSUPERUSER NOBYPASSRLS NOLOGIN; END IF; END $$;");
        sql("GRANT USAGE ON SCHEMA platform TO " + OWNER);
        sql("ALTER TABLE platform.scope OWNER TO " + OWNER);
        sql("ALTER TABLE platform.user_account OWNER TO " + OWNER);
        sql("ALTER TABLE platform.tag OWNER TO " + OWNER);
        sql("ALTER VIEW platform.scope_access OWNER TO " + OWNER);
        sql("ALTER VIEW platform.tag_access OWNER TO " + OWNER);

        sql("INSERT INTO platform.user_account (tenant_id, subject, email, role, status, muted) VALUES "
          + "('" + TENANT_A + "','" + WRITER   + "','" + WRITER   + "@example.test','member','active',false),"
          + "('" + TENANT_A + "','" + PEER     + "','" + PEER     + "@example.test','member','active',false),"
          + "('" + TENANT_A + "','" + MUTED    + "','" + MUTED    + "@example.test','member','active',true),"
          + "('" + TENANT_B + "','" + B_MEMBER + "','" + B_MEMBER + "@example.test','member','active',false)");
        sql("INSERT INTO platform.scope (tenant_id, name, slug, kind, created_by, locked) VALUES "
          + "('" + TENANT_A + "','Tag IT Project','" + PROJECT   + "','project','" + WRITER   + "',false),"
          + "('" + TENANT_A + "','Tag IT Locked','"  + LOCKED    + "','project','" + WRITER   + "',true),"
          + "('" + TENANT_B + "','Tag IT B','"       + B_PROJECT + "','project','" + B_MEMBER + "',false)");
        // Tenant A's one private scope (V1 seeds it; created here if a previous
        // suite removed it), authored by the peer: invisible to the writer.
        sql("INSERT INTO platform.scope (tenant_id, name, slug, kind) VALUES "
          + "('" + TENANT_A + "','private','private','private') ON CONFLICT DO NOTHING");
        sql("UPDATE platform.scope SET created_by = '" + PEER + "' "
          + "WHERE tenant_id = '" + TENANT_A + "' AND kind = 'private'");

        sql("INSERT INTO platform.tag (tenant_id, scope_id, token, created_by) "
          + "SELECT '" + TENANT_B + "', id, 'b-own', '" + B_MEMBER + "' FROM platform.scope "
          + "WHERE tenant_id = '" + TENANT_B + "' AND slug = '" + B_PROJECT + "'");
        sql("INSERT INTO platform.tag (tenant_id, scope_id, token, created_by) "
          + "SELECT '" + TENANT_B + "', id, 'b-stray', '" + B_MEMBER + "' FROM platform.scope "
          + "WHERE tenant_id = '" + TENANT_A + "' AND slug = '" + PROJECT + "'");
        sql("INSERT INTO platform.tag (tenant_id, scope_id, token, created_by) "
          + "SELECT '" + TENANT_A + "', id, 'private-only', '" + PEER + "' FROM platform.scope "
          + "WHERE tenant_id = '" + TENANT_A + "' AND kind = 'private'");

        for (String b : breaks) {
            sql(b);
        }
        sql("SET LOCAL SESSION AUTHORIZATION " + OWNER);
    }

    private void declareTheVisibleTwo() {
        tags.declare(WRITER, PROJECT, "component:api", null);
        tags.declare(WRITER, PROJECT, "alpha", null);
    }

    /**
     * The tenant test proper. The view first, as each service role sees it;
     * then the list; the refusal for the private scope last, because it marks
     * the transaction for rollback.
     */
    private void assertTenantIsolation() {
        for (String role : SERVICE_ROLES) {
            sql("SET LOCAL SESSION AUTHORIZATION " + role);
            sql("SELECT set_config('app.tenant_id', '" + TENANT_A + "', true)");
            sql("SELECT set_config('app.subject', '" + WRITER + "', true)");
            assertThat(strings("SELECT token FROM platform.tag_access ORDER BY token COLLATE \"C\""))
                .as("platform.tag_access as %s sees it for %s in tenant A", role, WRITER)
                .containsExactly("alpha", "component:api");
        }
        sql("SET LOCAL SESSION AUTHORIZATION " + OWNER);

        assertThat(tags.list(WRITER, PROJECT))
            .extracting(TagEntry::token)
            .as("the list of tenant A's project scope")
            .containsExactly("alpha", "component:api");

        String privateSlug = strings("SELECT slug FROM platform.scope WHERE kind = 'private'").get(0);
        assertThatThrownBy(() -> tags.list(WRITER, privateSlug))
            .as("a private scope authored by somebody else is not visible, so not listable")
            .isInstanceOf(ScopeRepository.ScopeNotFoundException.class);
    }

    /** {@code created_by,status,withdrawn_at} of one tag of the project scope, as one string. */
    private String row(String token) {
        List<String> rows = strings(
            "SELECT 'created_by=' || created_by || ',status=' || status "
          + "    || ',withdrawn_at=' || coalesce(withdrawn_at::text, 'null') "
          + "FROM platform.tag t JOIN platform.scope s ON s.id = t.scope_id "
          + "WHERE s.slug = '" + PROJECT + "' AND t.token = '" + token + "'");
        assertThat(rows).as("exactly one row for %s", token).hasSize(1);
        return rows.get(0);
    }

    /** Raw JDBC on the transaction's own connection — no Hibernate parsing of ':' in the text. */
    private void sql(String statement) {
        em.unwrap(Session.class).doWork(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(statement);
            }
        });
    }

    private List<String> strings(String query) {
        return em.unwrap(Session.class).doReturningWork(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }
}
