package ai.kumbuka.tenancy;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
 * RLS), and the within-tenant private invariant still holds.
 *
 * <p>Runs against a real Postgres (Quarkus DevServices container — no
 * Hibernate auto-mocking) so the RLS policies are actually exercised.
 * The four subtests below align with ADR-0011 §Verification:
 *
 * <ul>
 *   <li><b>Hibernate path.</b> {@link #hibernate_filter_isolates_tenants()}</li>
 *   <li><b>RLS path.</b> {@link #rls_isolates_tenants_via_session_guc()}</li>
 *   <li><b>Private invariant under tenancy.</b>
 *       {@link #private_invariant_holds_under_tenancy()}</li>
 *   <li><b>Write isolation.</b>
 *       {@link #write_with_cross_tenant_id_fails_closed_via_rls()}</li>
 * </ul>
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

    static final String CALLER_X = "user-x";

    @Inject MemoryRepository memories;
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
                s.execute("GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE);
                s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE);

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

    // -----------------------------------------------------------------------
    // Subtest (a) — Hibernate path.
    // -----------------------------------------------------------------------
    @Test
    void hibernate_filter_isolates_tenants() {
        plantMemories();

        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            List<Memory> rowsA = memories.recall(CALLER_X, "global", null, null, false);
            // Tenant A planted exactly one row in global.
            assertThat(rowsA).hasSize(1);
            assertThat(rowsA).allMatch(m -> TENANT_A.toString().equals(m.tenantId));
            assertThat(rowsA).noneMatch(m -> TENANT_B.toString().equals(m.tenantId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try (AutoCloseable ignored = tenantContext.bind(TENANT_B)) {
            List<Memory> rowsB = memories.recall(CALLER_X, "global", null, null, false);
            assertThat(rowsB).hasSize(1);
            assertThat(rowsB).allMatch(m -> TENANT_B.toString().equals(m.tenantId));
            assertThat(rowsB).noneMatch(m -> TENANT_A.toString().equals(m.tenantId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Subtest (b) — RLS path.
    // -----------------------------------------------------------------------
    @Test
    void rls_isolates_tenants_via_session_guc() throws SQLException {
        plantMemories();

        // Hibernate is the first layer of defence; this subtest goes
        // around it via raw JDBC and proves Layer 2 (RLS) holds.
        // Drops to a non-superuser role so RLS isn't bypassed.
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL SESSION AUTHORIZATION " + RLS_TEST_ROLE);
            }

            assertThat(countMemoriesUnderGuc(c, TENANT_A.toString())).isEqualTo(1L);
            assertThat(countMemoriesUnderGuc(c, TENANT_B.toString())).isEqualTo(1L);
            // Unset GUC → policy fails closed (NULL = anything is FALSE).
            assertThat(countMemoriesUnderGuc(c, null)).isZero();

            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // Subtest (c) — Private invariant under tenancy (M2).
    // The same subject string under both tenants must NOT cross over.
    // We exercise the real recall path used by /mcp.
    // -----------------------------------------------------------------------
    @Test
    void private_invariant_holds_under_tenancy() {
        plantPrivateUnderBothTenants();

        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            List<Memory> aPriv = memories.recall(CALLER_X, "private", null, null, false);
            assertThat(aPriv).hasSize(1);
            assertThat(aPriv.get(0).content).isEqualTo("secret in A");
            assertThat(aPriv.get(0).tenantId).isEqualTo(TENANT_A.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try (AutoCloseable ignored = tenantContext.bind(TENANT_B)) {
            List<Memory> bPriv = memories.recall(CALLER_X, "private", null, null, false);
            assertThat(bPriv).hasSize(1);
            assertThat(bPriv.get(0).content).isEqualTo("secret in B");
            assertThat(bPriv.get(0).tenantId).isEqualTo(TENANT_B.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Subtest (d) — Write isolation: an INSERT trying to set tenant_id
    // to a foreign tenant fails closed via RLS WITH CHECK.
    // -----------------------------------------------------------------------
    @Test
    void write_with_cross_tenant_id_fails_closed_via_rls() throws SQLException {
        UUID scopeIdA;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // Look up scope id as superuser (RLS bypassed for this read).
            try (var rs = s.executeQuery(
                "SELECT id FROM scope WHERE slug='global' AND tenant_id='" + TENANT_A + "'")) {
                rs.next();
                scopeIdA = UUID.fromString(rs.getString(1));
            }
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT_A + "', false)");
                // Drop to non-superuser so RLS actually fires.
                s.execute("SET LOCAL SESSION AUTHORIZATION " + RLS_TEST_ROLE);
            }

            assertThatThrownBy(() -> {
                try (Statement bad = c.createStatement()) {
                    // V16: row_id has a default (gen_random_uuid()), so it is
                    // omitted; logical_id + is_private are NOT NULL with no
                    // default, so they are supplied — otherwise a NOT-NULL
                    // violation could mask the RLS rejection this asserts.
                    bad.execute(
                        "INSERT INTO memory (tenant_id, owner_subject, scope_id, type, content, source, logical_id, is_private) "
                      + "VALUES ('" + TENANT_B + "', "
                      + "'" + CALLER_X + "', '" + scopeIdA + "', 'decision', "
                      + "'cross-tenant write attempt', 'mcp', gen_random_uuid(), false)");
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

    /** Plant one memory under each tenant in the global scope. NOT
     *  {@code @Transactional}: each {@code memories.remember} call opens
     *  its own TX via the repository method, so the {@code @TenantBound}
     *  interceptor sees the bind() that's active at TX-open time. */
    void plantMemories() {
        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            memories.remember(CALLER_X, "global", MemoryType.DECISION,
                "shared.key.a", "row in tenant A", SourceChannel.MCP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try (AutoCloseable ignored = tenantContext.bind(TENANT_B)) {
            memories.remember(CALLER_X, "global", MemoryType.DECISION,
                "shared.key.b", "row in tenant B", SourceChannel.MCP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Plant a private memory for the SAME subject string under both
     *  tenants — the subtest (c) scenario the brief calls out. */
    void plantPrivateUnderBothTenants() {
        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            memories.remember(CALLER_X, "private", MemoryType.DECISION,
                "priv.a", "secret in A", SourceChannel.MCP);
            memories.remember(CALLER_X, "global", MemoryType.DECISION,
                "shared.a", "non-secret in A", SourceChannel.MCP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try (AutoCloseable ignored = tenantContext.bind(TENANT_B)) {
            memories.remember(CALLER_X, "private", MemoryType.DECISION,
                "priv.b", "secret in B", SourceChannel.MCP);
            memories.remember(CALLER_X, "global", MemoryType.DECISION,
                "shared.b", "non-secret in B", SourceChannel.MCP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long countMemoriesUnderGuc(Connection c, String tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (tenant == null) {
                s.execute("RESET app.tenant_id");
            } else {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            }
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM memory")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // Silences `unused-import` warnings during incremental refactors —
    // we may use the ScopeKind enum directly in a later subtest.
    @SuppressWarnings("unused")
    private static final ScopeKind UNUSED = ScopeKind.GLOBAL;
}
