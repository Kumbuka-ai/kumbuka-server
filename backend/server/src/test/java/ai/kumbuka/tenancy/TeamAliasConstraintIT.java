package ai.kumbuka.tenancy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour gate for V7 {@code team.alias}: shape, uniqueness,
 * and the deliberate carve-outs.
 *
 * <p>The reserved-alias <em>list</em> (console/ops/auth/...) is an
 * application-layer concern in ops-console's TenantProvisioningService —
 * NOT a DB constraint — so this IT does not assert any specific reserved
 * name. What it does assert is the structural shape the DB enforces:
 *
 * <ul>
 *   <li>regex: lowercase alnum + ASCII hyphens, alnum at both ends,
 *       length 3..32;</li>
 *   <li>punycode/IDN ban: {@code xn--…} is rejected (no homoglyph
 *       hostname can land in the routing key);</li>
 *   <li>uniqueness: two tenants cannot share an alias;</li>
 *   <li>the {@code 'default'} carve-out — the literal that V7 backfills
 *       on the singleton CE seed row — is accepted even though it is
 *       only 7 chars and would otherwise still pass the regex anyway.
 *       Asserting this guards against accidental tightening of the
 *       constraint that would make the migration itself fail.</li>
 * </ul>
 *
 * <p>Runs against the real Postgres provided by Quarkus DevServices
 * (matching {@link CrossTenantIsolationIT}). We INSERT directly via JDBC
 * because the CHECK constraint is the contract under test — going
 * through the {@link ai.kumbuka.domain.Team} entity would invoke
 * Hibernate's own validation first and obscure which layer rejected.
 */
@QuarkusTest
@Tag("integration")
class TeamAliasConstraintIT {

    @Inject AgroalDataSource dataSource;

    @Test
    void seed_row_backfilled_to_default_by_v7() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT alias FROM team WHERE id = '00000000-0000-0000-0000-000000000001'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("default");
        }
    }

    @Test
    void accepts_wellshaped_alias() {
        assertThatInsertedAliasSucceeds("acme");
        assertThatInsertedAliasSucceeds("acme-corp");
        assertThatInsertedAliasSucceeds("a1b2c3-d4e5");
        assertThatInsertedAliasSucceeds("abcdef0123456789abcdef0123456789"); // 32 chars
    }

    @Test
    void rejects_shape_violations() {
        // too short (<3)
        assertThatInsertFailsForAlias("ab");
        // leading hyphen
        assertThatInsertFailsForAlias("-acme");
        // trailing hyphen
        assertThatInsertFailsForAlias("acme-");
        // uppercase
        assertThatInsertFailsForAlias("Acme");
        // underscore
        assertThatInsertFailsForAlias("acme_corp");
        // empty
        assertThatInsertFailsForAlias("");
        // too long (>32)
        assertThatInsertFailsForAlias("a".repeat(33));
    }

    @Test
    void rejects_punycode_prefix_even_if_otherwise_wellshaped() {
        // xn-- is a valid hyphen-bearing ASCII shape by regex, but the
        // CHECK explicitly bans it so no homoglyph hostname can sneak in.
        assertThatInsertFailsForAlias("xn--acme");
    }

    @Test
    void rejects_duplicate_alias() throws SQLException {
        final UUID idA = insertTeamWithAlias("dup-1");
        try {
            assertThatThrownBy(() -> insertTeamWithAlias("dup-1"))
                .isInstanceOf(SQLException.class);
        } finally {
            cleanupTeam(idA);
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void assertThatInsertedAliasSucceeds(String alias) {
        UUID id = null;
        try {
            id = insertTeamWithAlias(alias);
            assertThat(id).isNotNull();
        } catch (SQLException e) {
            throw new AssertionError("INSERT should have succeeded for alias=" + alias, e);
        } finally {
            if (id != null) cleanupTeam(id);
        }
    }

    private void assertThatInsertFailsForAlias(String alias) {
        assertThatThrownBy(() -> insertTeamWithAlias(alias))
            .as("INSERT should have been rejected for alias=" + alias)
            .isInstanceOf(SQLException.class);
    }

    /**
     * Insert a team row directly via JDBC. We set the per-session
     * {@code app.tenant_id} GUC so RLS WITH CHECK lets the row land and
     * the only remaining gate is the {@code team_alias_format} +
     * {@code team_alias_unique} constraints under test.
     */
    private UUID insertTeamWithAlias(String alias) throws SQLException {
        final UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + id + "', true)");
                // PreparedStatement would be safer, but the CHECK is what
                // we are testing — keep the SQL the same shape that the
                // migration speaks about.
                s.execute(
                    "INSERT INTO team (id, tenant_id, name, alias) VALUES ("
                  + "'" + id + "', '" + id + "', 'Test', '" + alias.replace("'", "''") + "')");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        return id;
    }

    private void cleanupTeam(UUID id) {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM team WHERE id = '" + id + "'");
        } catch (SQLException ignored) {
            // best-effort — DevServices DB is thrown away after the run anyway.
        }
    }
}
