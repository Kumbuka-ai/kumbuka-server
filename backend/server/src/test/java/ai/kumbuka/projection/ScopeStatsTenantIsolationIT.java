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
 * tenants' memory planted, a refresh bound to one must project ONLY that
 * tenant's rows and must never write or retain the other's. Without the
 * predicate (or if a future multi-tenant refresher dropped it), the unscoped
 * {@code SELECT FROM memory} would pull the other tenant's rows under the
 * superuser datasource and this test would fail.
 *
 * <p>Uses two dedicated tenants (C/D) that no other IT touches — the projection
 * + planting must not pollute the singleton/Tenant-B rows that
 * {@code CrossTenantIsolationIT} counts on (all ITs share one DevServices DB).
 */
@QuarkusTest
@Tag("integration")
class ScopeStatsTenantIsolationIT {

    static final UUID TENANT_C = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID TENANT_D = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final String CALLER = "user-iso";

    @Inject ScopeStatsRefresher refresher;
    @Inject MemoryRepository memories;
    @Inject TenantContext tenantContext;
    @Inject AgroalDataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        seedTenant(TENANT_C, "scopestats-iso-c");
        seedTenant(TENANT_D, "scopestats-iso-d");
        plantGlobal(TENANT_C, "iso.c", "row in tenant C");
        plantGlobal(TENANT_D, "iso.d", "row in tenant D");
    }

    /** Seed a tenant's team + global scope + settings inside one tx with the GUC
     *  set to that tenant (RLS WITH CHECK). is_local=true → resets at commit. */
    private void seedTenant(UUID tenant, String alias) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', true)");
                s.execute("INSERT INTO team (id, tenant_id, name, alias) VALUES "
                    + "('" + tenant + "', '" + tenant + "', 'Team " + alias + "', '" + alias + "') "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO scope (tenant_id, slug, name, kind, fixed) VALUES "
                    + "('" + tenant + "', 'global', 'global', 'global', true) "
                    + "ON CONFLICT DO NOTHING");
                s.execute("INSERT INTO team_settings (tenant_id) VALUES ('" + tenant + "') "
                    + "ON CONFLICT (tenant_id) DO NOTHING");
            }
            c.commit();
        }
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
        try (AutoCloseable ignored = tenantContext.bind(TENANT_C)) {
            refresher.refresh();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Read as the superuser test datasource (RLS bypassed → sees all rows).
        assertThat(countScopeStats(TENANT_C))
            .as("tenant C's global scope must be projected")
            .isPositive();
        assertThat(countScopeStats(TENANT_D))
            .as("a refresh bound to C must not write or retain tenant D's scope_stats — the "
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
