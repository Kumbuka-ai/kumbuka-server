package ai.kumbuka.migration;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Idempotency gate for V19 (content-unit channel + deleted flag).
 *
 * <p>V19 must reach the same end state whether it runs against a pristine
 * database (the {@code existence} column still present, the two partial unique
 * indexes on the {@code existence} predicate, the channel CHECKs still narrow)
 * OR against a database where part of the change was already applied out of
 * band by an operator — {@code existence} already dropped and {@code is_deleted}
 * already present, the two partial unique indexes either already rebuilt on the
 * boolean predicate or missing entirely, the channel CHECKs still narrow. A
 * robust migration survives that half-applied shape without a manual step; this
 * IT pins that property so a future edit cannot silently break it.
 *
 * <p>The migration runs at boot in version order on the real {@code public}
 * schema, so {@code public.memory} is the authoritative post-V19 reference.
 * Each scenario builds a starting shape in an isolated scratch schema, runs the
 * ACTUAL V19 script (read from the classpath) against it, and asserts the
 * resulting constraints, indexes and columns equal the {@code public} reference.
 * Running against a scratch schema keeps the shared DevServices database
 * untouched; V19's own catalog checks resolve {@code memory} through the
 * search path, so they operate on the scratch table.
 */
@QuarkusTest
@Tag("integration")
class ContentUnitChannelDeletedFlagMigrationIT {

    @Inject AgroalDataSource dataSource;

    private static final String V19 = readMigration(
            "/db/migration/V19__content_unit_channel_and_deleted_flag.sql");

    /** Columns V19 touches, plus the two narrow channel CHECKs it widens. */
    private static final String COLUMNS_AND_CHECKS = """
              scope_id       uuid        NOT NULL,
              owner_subject  text        NOT NULL,
              key            text,
              is_head        boolean     NOT NULL DEFAULT true,
              is_private     boolean     NOT NULL DEFAULT false,
              source         varchar(16) NOT NULL DEFAULT 'mcp'
                CONSTRAINT memory_source_check CHECK (source IN ('console','mcp','system')),
              updated_source varchar(16)
                CONSTRAINT memory_updated_source_check CHECK (updated_source IN ('console','mcp','system'))
            """;

    /** Path A — pristine pre-V19 shape: {@code existence} present, indexes on its predicate. */
    private static final String START_PRISTINE =
            "CREATE TABLE memory (\n" + COLUMNS_AND_CHECKS + ",\n"
            + "  existence varchar(16) NOT NULL DEFAULT 'active');\n"
            + "CREATE UNIQUE INDEX uq_memory_shared_key ON memory (scope_id, key)\n"
            + "  WHERE is_head AND existence = 'active' AND NOT is_private AND key IS NOT NULL;\n"
            + "CREATE UNIQUE INDEX uq_memory_private_key ON memory (scope_id, owner_subject, key)\n"
            + "  WHERE is_head AND existence = 'active' AND is_private AND key IS NOT NULL;";

    /** Path B — out-of-band shape, indexes already rebuilt by hand on the boolean predicate. */
    private static final String START_PARTIAL_WITH_INDEXES =
            "CREATE TABLE memory (\n" + COLUMNS_AND_CHECKS + ",\n"
            + "  is_deleted boolean NOT NULL DEFAULT false);\n"
            + "CREATE UNIQUE INDEX uq_memory_shared_key ON memory (scope_id, key)\n"
            + "  WHERE is_head AND NOT is_deleted AND NOT is_private AND key IS NOT NULL;\n"
            + "CREATE UNIQUE INDEX uq_memory_private_key ON memory (scope_id, owner_subject, key)\n"
            + "  WHERE is_head AND NOT is_deleted AND is_private AND key IS NOT NULL;";

    /** Path C — out-of-band shape before the hand repair: the two indexes missing entirely. */
    private static final String START_PARTIAL_NO_INDEXES =
            "CREATE TABLE memory (\n" + COLUMNS_AND_CHECKS + ",\n"
            + "  is_deleted boolean NOT NULL DEFAULT false);";

    @Test
    void pathA_pristine_reaches_reference_end_state() throws SQLException {
        runInScratchSchema("v19_path_a", START_PRISTINE);
    }

    @Test
    void pathB_partial_with_indexes_reaches_reference_end_state() throws SQLException {
        runInScratchSchema("v19_path_b", START_PARTIAL_WITH_INDEXES);
    }

    @Test
    void pathC_partial_without_indexes_reaches_reference_end_state() throws SQLException {
        runInScratchSchema("v19_path_c", START_PARTIAL_NO_INDEXES);
    }

    @Test
    void guard_still_fires_on_nondefault_existence_row() throws SQLException {
        final String schema = "v19_guard";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            s.execute("SET search_path TO " + schema + ", public");
            s.execute(START_PRISTINE);
            s.execute("INSERT INTO memory (scope_id, owner_subject, existence) "
                    + "VALUES (gen_random_uuid(), 'someone', 'deleted')");
            // The fail-loud guard is conditional on the column's presence but NOT removed:
            // with existence present and a non-default row it must still abort.
            assertThatThrownBy(() -> { try (Statement s2 = c.createStatement()) { s2.execute(V19); } })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("not in the active state");
            s.execute("RESET search_path");
        } finally {
            dropSchema(schema);
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Build the start shape, run V19, assert the reference end state — then run V19 a second
     *  time and assert again, so a literal re-execution is proven a safe no-op. */
    private void runInScratchSchema(String schema, String startShape) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            s.execute("SET search_path TO " + schema + ", public");
            s.execute(startShape);

            s.execute(V19);
            assertReferenceEndState(c, schema);

            s.execute(V19); // idempotent on re-execution
            assertReferenceEndState(c, schema);

            s.execute("RESET search_path");
        } finally {
            dropSchema(schema);
        }
    }

    private void assertReferenceEndState(Connection c, String schema) throws SQLException {
        // Column swap complete: is_deleted present, existence gone.
        assertThat(columnExists(c, schema, "is_deleted")).as("is_deleted present").isTrue();
        assertThat(columnExists(c, schema, "existence")).as("existence dropped").isFalse();

        // Both channel CHECKs widened to admit 'import', byte-identical to the public reference.
        assertThat(constraintDef(c, schema, "memory_source_check"))
                .as("memory_source_check")
                .isEqualTo(constraintDef(c, "public", "memory_source_check"))
                .contains("import");
        assertThat(constraintDef(c, schema, "memory_updated_source_check"))
                .as("memory_updated_source_check")
                .isEqualTo(constraintDef(c, "public", "memory_updated_source_check"))
                .contains("import");

        // Both partial unique indexes present, on the boolean predicate, matching the reference.
        assertThat(indexPredicate(c, schema, "uq_memory_shared_key"))
                .as("uq_memory_shared_key predicate")
                .isEqualTo(indexPredicate(c, "public", "uq_memory_shared_key"))
                .contains("is_deleted").doesNotContain("existence");
        assertThat(indexPredicate(c, schema, "uq_memory_private_key"))
                .as("uq_memory_private_key predicate")
                .isEqualTo(indexPredicate(c, "public", "uq_memory_private_key"))
                .contains("is_deleted").doesNotContain("existence");
    }

    private boolean columnExists(Connection c, String schema, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'memory' AND column_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private String constraintDef(Connection c, String schema, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pg_get_constraintdef(con.oid) "
                + "FROM pg_constraint con "
                + "JOIN pg_class cl ON cl.oid = con.conrelid "
                + "JOIN pg_namespace ns ON ns.oid = cl.relnamespace "
                + "WHERE ns.nspname = ? AND cl.relname = 'memory' AND con.conname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    /** The partial index WHERE predicate, schema-independent (references columns, not the schema). */
    private String indexPredicate(Connection c, String schema, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pg_get_expr(ix.indpred, ix.indrelid) "
                + "FROM pg_index ix "
                + "JOIN pg_class cl ON cl.oid = ix.indexrelid "
                + "JOIN pg_namespace ns ON ns.oid = cl.relnamespace "
                + "WHERE ns.nspname = ? AND cl.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static String readMigration(String resource) {
        try (InputStream in = ContentUnitChannelDeletedFlagMigrationIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("migration resource not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read migration resource: " + resource, e);
        }
    }
}
