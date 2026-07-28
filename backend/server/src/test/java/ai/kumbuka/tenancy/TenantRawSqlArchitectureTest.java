package ai.kumbuka.tenancy;

import ai.kumbuka.waitlist.WaitlistIntakeResource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
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
 *
 * <p><strong>Presence is decided on the APPLIED annotation, via reflection —
 * never on the file text.</strong> The file walk is used only to discover which
 * classes issue raw SQL; whether such a class is tenant-bound is read from the
 * loaded {@link Class} ({@link #isTenantBound}). An earlier version matched the
 * literal {@code "@TenantBound"} anywhere in the source, so any class that merely
 * named the annotation in a comment, javadoc, or string passed unguarded — the
 * public waitlist intake did exactly that. A check that derives its expectation
 * from the artifact it is checking proves nothing (WORKING_CONCEPT §16 rule 5);
 * reflection makes the guard immune to comments and literals by construction.
 */
class TenantRawSqlArchitectureTest {

    /**
     * Classes allowed to issue raw SQL WITHOUT an applied {@code @TenantBound},
     * each with the reason it cannot leak tenant data. Entries are {@link Class}
     * literals (not names) so a rename or deletion breaks the build instead of
     * silently leaving a dead, unmatchable exception behind.
     */
    private static final Set<Class<?>> ALLOWLIST = Set.of(
        // Sets the app.tenant_id GUC itself via set_config('app.tenant_id', …,
        // is_local=true); touches no tenant table, so there is no row to leak.
        TenantDatabaseBinding.class,
        // Flyway beforeEachMigrate callback: binds the singleton tenant GUC at
        // boot, before any traffic is served (is_local=true, migration tx only).
        TenantyMigrationCallback.class,
        // PUBLIC pre-tenant waitlist intake: INSERT-only into ops.waitlist_entry,
        // control-plane data with NO app.tenant_id column (D-OPS-32 / waitlist-UTM).
        // The per-tenant RLS seam does not apply, so @TenantBound would be
        // meaningless here; abuse is contained by the Caddy edge rate limit.
        // Confirmed SAFE by ratified decision D-OPS-32 — annotating it to satisfy
        // the guard would be a lie, not a fix.
        WaitlistIntakeResource.class);

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
                // File text locates raw-SQL usage ONLY. Whether the class is
                // tenant-bound is decided structurally below, from the applied
                // annotation — a comment, javadoc, or string literal must never
                // be able to green a class.
                String src = Files.readString(file);
                boolean issuesRawSql = RAW_SQL_MARKERS.stream().anyMatch(src::contains);
                if (!issuesRawSql) {
                    continue;
                }
                Class<?> clazz = loadClass(root, file);
                if (clazz == null) {
                    // A raw-SQL file we cannot load cannot be structurally
                    // verified — fail loud rather than skip it silently.
                    offenders.add(fqcn(root, file)
                        + " (issues raw SQL but could not be loaded for a structural @TenantBound check)");
                    continue;
                }
                if (ALLOWLIST.contains(clazz)) {
                    continue;
                }
                if (!isTenantBound(clazz)) {
                    offenders.add(clazz.getName() + " (" + root.relativize(file) + ")");
                }
            }
        }

        assertThat(offenders)
            .as("raw/native SQL bypasses the Hibernate @TenantId filter, so it must run under "
                + "@TenantBound (which sets the app.tenant_id GUC for RLS). Presence is checked on the "
                + "APPLIED annotation via reflection, not the file text — a comment or javadoc that merely "
                + "names @TenantBound does not count. These classes issue raw SQL without an applied "
                + "@TenantBound and risk cross-tenant reads/writes — add @TenantBound, or if the class "
                + "legitimately manages the GUC itself add it to ALLOWLIST with a reason.")
            .isEmpty();
    }

    /**
     * The applied-annotation check: {@code @TenantBound} at the class (TYPE)
     * level covers every method; a method-level annotation covers that method.
     * Either satisfies the tripwire. Unlike a substring match this cannot be
     * fooled by the literal appearing in a comment, javadoc, or string.
     */
    private static boolean isTenantBound(Class<?> clazz) {
        if (clazz.isAnnotationPresent(TenantBound.class)) {
            return true;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(TenantBound.class)) {
                return true;
            }
        }
        return false;
    }

    /** Derive the fully-qualified class name from the path under the source root. */
    private static String fqcn(Path root, Path file) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(part);
        }
        return sb.substring(0, sb.length() - ".java".length());
    }

    /**
     * Load the class for a source file WITHOUT initializing it (no static
     * initializers, no CDI) — annotation reflection needs the class linked, not
     * initialized. Returns {@code null} when the file has no loadable top-level
     * class of the expected name (e.g. {@code package-info}); the caller then
     * treats a raw-SQL file it cannot verify as an offender rather than skipping.
     */
    private static Class<?> loadClass(Path root, Path file) {
        try {
            return Class.forName(fqcn(root, file), false,
                TenantRawSqlArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Not loadable as a named top-level class (e.g. package-info). The
            // caller turns this null into a loud offender, so the failure is
            // surfaced there, not swallowed here.
            return null;
        }
    }
}
