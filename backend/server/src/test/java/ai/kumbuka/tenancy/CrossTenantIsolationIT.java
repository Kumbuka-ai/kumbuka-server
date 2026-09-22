package ai.kumbuka.tenancy;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.repo.ScopeRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance gate for ADR-0011: cross-tenant isolation under the two
 * structural enforcement layers (Hibernate {@code @TenantId} + Postgres
 * RLS).
 *
 * <p>Runs against a real Postgres (Quarkus DevServices container — no
 * Hibernate auto-mocking) so the RLS policies are actually exercised.
 * The three subtests below align with ADR-0011 §Verification:
 *
 * <ul>
 *   <li><b>Hibernate path.</b> {@link #hibernate_filter_isolates_tenants()}</li>
 *   <li><b>RLS path.</b> {@link #rls_isolates_tenants_via_session_guc()}</li>
 *   <li><b>Write isolation.</b>
 *       {@link #write_with_cross_tenant_id_fails_closed_via_rls()}</li>
 * </ul>
 *
 * <p><b>The carrier is the scope table.</b> This gate rode on {@code memory}
 * rows until the memory engine left the core — the table was simply the
 * handiest tenant-scoped, RLS'd thing to plant. What it asserts has never been
 * about entries: it is that the tenant axis holds on both layers, and
 * {@code scope} carries {@code @TenantId} and the same V3 policy. A fourth
 * subtest asserted the within-tenant private-content invariant; that one was
 * about content, and it went with the content.
 *
 * <p>Tagged {@code integration}; the {@code integration} Maven profile
 * runs {@code *IT.java} via failsafe.
 */
@QuarkusTest
@Tag("integration")
class CrossTenantIsolationIT {

    /** Singleton tenant seeded by V1__init.sql. */
    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Second tenant seeded directly by {@link #seedTenantB}. */
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Slugs planted by this test, self-quarantined against the shared DevServices DB. */
    static final String SLUG_A = "xtenant-it-a";
    static final String SLUG_B = "xtenant-it-b";

    @Inject ScopeRepository scopes;
    @Inject TenantContext tenantContext;
    @Inject AgroalDataSource dataSource;

    /**
     * Quarkus DevServices runs Postgres with a superuser app account, and
     * superusers bypass RLS (BYPASSRLS attribute). The RLS subtests below
     * use {@code SET LOCAL SESSION AUTHORIZATION} to drop into a
     * non-superuser role for the queries that need to feel the policy.
     */
    static final String RLS_TEST_ROLE = "rls_test_user";

    /**
     * Seed tenant B's team + scopes + settings row via direct JDBC. The
     * cross-tenant test plants real data under both tenants and proves the
     * isolation; we set the session GUC to B inside the connection so RLS
     * WITH CHECK lets the inserts land.
     */
    @BeforeEach
    void seedTenantB() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                // Ensure the dedicated non-superuser role exists so the
                // RLS subtests can SET LOCAL SESSION AUTHORIZATION to it.
                s.execute(
                    "DO $$ BEGIN "
                  + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + RLS_TEST_ROLE + "') THEN"
                  + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS NOINHERIT;"
                  + "  END IF; "
                  + "END $$;");
                // Since V23 the tenancy inventory lives in `platform`, so the
                // role needs it too. Without USAGE on the schema Postgres
                // reports the table as "does not exist", which would look like
                // a missing migration rather than a missing grant.
                s.execute("GRANT USAGE ON SCHEMA public, platform TO " + RLS_TEST_ROLE);
                s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE);
                s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform TO " + RLS_TEST_ROLE);

                s.execute("SELECT set_config('app.tenant_id', '" + TENANT_B + "', false)");
                s.execute("INSERT INTO team (id, tenant_id, name, alias) VALUES "
                    + "('00000000-0000-0000-0000-000000000002', '" + TENANT_B + "', 'Team B', 'team-b') "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO scope (tenant_id, slug, name, kind, fixed) VALUES "
                    + "('" + TENANT_B + "', 'global', 'global', 'global', true), "
                    + "('" + TENANT_B + "', 'private', 'private', 'private', false) "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO team_settings (tenant_id) VALUES "
                    + "('" + TENANT_B + "') "
                    + "ON CONFLICT (tenant_id) DO NOTHING");
            }
            c.commit();
        }
    }

    /** Remove only what this test planted — the shared DevServices DB is reused. */
    @AfterEach
    void dropPlantedScopes() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM platform.scope WHERE slug IN ('" + SLUG_A + "','" + SLUG_B + "')");
        }
    }

    // -----------------------------------------------------------------------
    // Subtest (a) — Hibernate path.
    // -----------------------------------------------------------------------
    @Test
    void hibernate_filter_isolates_tenants() {
        plantScopes();

        List<String> slugsA = slugsUnder(TENANT_A);
        assertThat(slugsA).contains(SLUG_A);
        assertThat(slugsA)
            .as("tenant A's ORM read must never reach a row tenant B planted")
            .doesNotContain(SLUG_B);

        List<String> slugsB = slugsUnder(TENANT_B);
        assertThat(slugsB).contains(SLUG_B);
        assertThat(slugsB)
            .as("tenant B's ORM read must never reach a row tenant A planted")
            .doesNotContain(SLUG_A);
    }

    // -----------------------------------------------------------------------
    // Subtest (b) — RLS path.
    // -----------------------------------------------------------------------
    @Test
    void rls_isolates_tenants_via_session_guc() throws SQLException {
        plantScopes();

        // Hibernate is the first layer of defence; this subtest goes
        // around it via raw JDBC and proves Layer 2 (RLS) holds.
        // Drops to a non-superuser role so RLS isn't bypassed.
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL SESSION AUTHORIZATION " + RLS_TEST_ROLE);
            }

            assertThat(countPlantedUnderGuc(c, TENANT_A.toString())).isEqualTo(1L);
            assertThat(countPlantedUnderGuc(c, TENANT_B.toString())).isEqualTo(1L);
            // Unset GUC → policy fails closed (NULL = anything is FALSE).
            assertThat(countPlantedUnderGuc(c, null)).isZero();

            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // Subtest (c) — Write isolation: an INSERT trying to set tenant_id
    // to a foreign tenant fails closed via RLS WITH CHECK.
    // -----------------------------------------------------------------------
    @Test
    void write_with_cross_tenant_id_fails_closed_via_rls() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT_A + "', false)");
                // Drop to non-superuser so RLS actually fires.
                s.execute("SET LOCAL SESSION AUTHORIZATION " + RLS_TEST_ROLE);
            }

            assertThatThrownBy(() -> {
                try (Statement bad = c.createStatement()) {
                    // Every NOT NULL column without a default is supplied, so a
                    // NOT-NULL violation cannot mask the RLS rejection asserted here.
                    bad.execute(
                        "INSERT INTO platform.scope (tenant_id, slug, name, kind, fixed, archived) "
                      + "VALUES ('" + TENANT_B + "', 'xtenant-write-attempt', "
                      + "'cross-tenant write attempt', 'project', false, false)");
                }
            })
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("row-level security");

            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Plant one project scope under each tenant. NOT {@code @Transactional}:
     *  {@code createProject} opens its own TX via the repository method, so the
     *  {@code @TenantBound} interceptor sees the bind() active at TX-open time. */
    void plantScopes() {
        plantScope(TENANT_A, SLUG_A);
        plantScope(TENANT_B, SLUG_B);
    }

    private void plantScope(UUID tenant, String slug) {
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            scopes.createProject(slug, slug, null, "xtenant-it-seed");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Every scope slug the ORM hands out while bound to {@code tenant}. */
    private List<String> slugsUnder(UUID tenant) {
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            return scopes.listAll().stream().map(s -> s.slug).toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long countPlantedUnderGuc(Connection c, String tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (tenant == null) {
                s.execute("RESET app.tenant_id");
            } else {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            }
            try (var rs = s.executeQuery(
                    "SELECT COUNT(*) FROM platform.scope "
                  + "WHERE slug IN ('" + SLUG_A + "','" + SLUG_B + "')")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Referenced so the tenant-scoped entity type stays pinned to this gate. */
    @SuppressWarnings("unused")
    private static final Class<Scope> CARRIER = Scope.class;
}
