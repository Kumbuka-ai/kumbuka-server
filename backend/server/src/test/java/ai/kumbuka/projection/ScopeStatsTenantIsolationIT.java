package ai.kumbuka.projection;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.tenancy.TenantContext;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant-isolation gate for the {@code scope_stats} projection — the one
 * tenant-scoped table with no Hibernate {@code @TenantId} entity, written only
 * by {@link ScopeStatsRefresher}'s native SQL.
 *
 * <p>The DevServices Postgres runs as a superuser (RLS bypassed), so this IT
 * exercises the refresher's <em>explicit</em> {@code tenant_id} predicate — the
 * defence-in-depth layer that must hold even when RLS does not. With two
 * tenants' memory planted, a refresh bound to tenant A must project ONLY tenant
 * A's rows and must never write or retain tenant B's. Without the predicate (or
 * if a future multi-tenant refresher dropped it), the unscoped
 * {@code SELECT FROM memory} would pull B's rows under the superuser datasource
 * and this test would fail.
 */
@QuarkusTest
@Tag("integration")
class ScopeStatsTenantIsolationIT {

    /** Singleton tenant seeded by V1__init.sql. */
    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Second tenant seeded directly below. */
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String CALLER = "user-iso";

    @Inject ScopeStatsRefresher refresher;
    @Inject MemoryRepository memories;
    @Inject TenantContext tenantContext;
    @Inject AgroalDataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        // Seed tenant B's team + global scope + settings inside one tx with the
        // GUC set to B (so RLS WITH CHECK accepts the inserts). is_local=true →
        // resets at commit, leaves no residue on the pooled connection.
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT_B + "', true)");
                s.execute("INSERT INTO team (id, tenant_id, name, alias) VALUES "
                    + "('00000000-0000-0000-0000-000000000002', '" + TENANT_B + "', 'Team B', 'team-b') "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO scope (tenant_id, slug, name, kind, fixed) VALUES "
                    + "('" + TENANT_B + "', 'global', 'global', 'global', true) "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO team_settings (tenant_id) VALUES ('" + TENANT_B + "') "
                    + "ON CONFLICT (tenant_id) DO NOTHING");
            }
            c.commit();
        }
        // Plant one shared (global) memory under each tenant via the real repo.
        plantGlobal(TENANT_A, "iso.a", "row in tenant A");
        plantGlobal(TENANT_B, "iso.b", "row in tenant B");
    }

    private void plantGlobal(UUID tenant, String key, String content) {
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            memories.remember(CALLER, "global", MemoryType.DECISION, key, content, SourceChannel.MCP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void refresh_bound_to_one_tenant_never_touches_another() throws SQLException {
        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            refresher.refresh();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Read as the superuser test datasource (RLS bypassed → sees all rows).
        assertThat(countScopeStats(TENANT_A))
            .as("tenant A's global scope must be projected")
            .isPositive();
        assertThat(countScopeStats(TENANT_B))
            .as("a refresh bound to A must not write or retain tenant B's scope_stats — the "
                + "explicit tenant_id predicate is the defence-in-depth that holds without RLS")
            .isZero();
    }

    private long countScopeStats(UUID tenant) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT COUNT(*) FROM scope_stats WHERE tenant_id = '" + tenant + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
