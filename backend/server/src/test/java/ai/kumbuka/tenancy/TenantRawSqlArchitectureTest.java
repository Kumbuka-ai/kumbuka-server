package ai.kumbuka.tenancy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture tripwire for tenant isolation (top-priority invariant: data must
 * never flow between tenants).
 *
 * <p>Tenant rows are protected by TWO layers: the Hibernate {@code @TenantId}
 * filter (applied to every ORM/Panache query) AND Postgres RLS keyed on the
 * {@code app.tenant_id} GUC. <strong>Raw / native SQL bypasses the first
 * layer</strong> — it is NOT rewritten with {@code WHERE tenant_id = …} — so its
 * only protection is the RLS GUC. That GUC is set exclusively inside
 * {@code @TenantBound @Transactional} methods. Therefore any class that issues
 * raw SQL against the database must be {@code @TenantBound}; otherwise it can
 * read or write under a stale/foreign tenant.
 *
 * <p>This test fails the moment a new class issues {@code createNativeQuery} /
 * raw JDBC without {@code @TenantBound}. That forces a reviewer to either add
 * {@code @TenantBound} or, if the class legitimately manages the GUC itself,
 * extend {@link #ALLOWLIST} with a documented reason. It is the structural
 * backstop that makes the June-2026 "raw SQL under the wrong tenant" bug class
 * impossible to merge unnoticed.
 */
class TenantRawSqlArchitectureTest {

    /**
     * Classes allowed to issue raw SQL WITHOUT {@code @TenantBound}, each with
     * the reason it cannot leak tenant data.
     */
    private static final Set<String> ALLOWLIST = Set.of(
        // Sets the app.tenant_id GUC itself via set_config — touches no table.
        "TenantDatabaseBinding",
        // Flyway beforeEachMigrate: binds the singleton tenant GUC at boot,
        // before any traffic is served; is_local=true, migration tx only.
        "TenantyMigrationCallback");

    private static final List<String> RAW_SQL_MARKERS = List.of(
        "createNativeQuery", ".getConnection(", ".createStatement(", ".prepareStatement(");

    @Test
    void raw_sql_is_only_issued_from_tenant_bound_classes() throws IOException {
        Path root = Files.exists(Paths.get("src/main/java"))
            ? Paths.get("src/main/java")
            : Paths.get("backend/server/src/main/java");
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist (run from the module dir)", root)
            .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(file);
                boolean issuesRawSql = RAW_SQL_MARKERS.stream().anyMatch(src::contains);
                if (!issuesRawSql) {
                    continue;
                }
                String className = file.getFileName().toString().replace(".java", "");
                if (ALLOWLIST.contains(className)) {
                    continue;
                }
                if (!src.contains("@TenantBound")) {
                    offenders.add(className + " (" + root.relativize(file) + ")");
                }
            }
        }

        assertThat(offenders)
            .as("raw/native SQL bypasses the Hibernate @TenantId filter, so it must run under "
                + "@TenantBound (which sets the app.tenant_id GUC for RLS). These classes issue raw "
                + "SQL without @TenantBound and risk cross-tenant reads/writes — add @TenantBound, or "
                + "if the class legitimately manages the GUC itself add it to ALLOWLIST with a reason.")
            .isEmpty();
    }
}
