package ai.kumbuka.tenancy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Witnesses the tenant-binding Flyway callback.
 *
 * <h2>Why this needs its own test</h2>
 *
 * The Quarkus Flyway extension resolves callbacks from
 * {@code quarkus.flyway.callbacks} by class name and instantiates them
 * reflectively through the no-argument constructor. It does <strong>not</strong>
 * discover them as CDI beans. A callback that is written, annotated and never
 * named in that line is simply never registered — with no warning, no error,
 * and migrations that run happily without it.
 *
 * <p>That failure stays invisible for as long as the migrating role is
 * privileged enough to walk past row-level security, which is what the deployed
 * migrator does today. It becomes visible the moment that role is
 * de-privileged. The assurance the callback is supposed to give — a backstop
 * for a migration that forgets to bind its tenant — is therefore what is under
 * test here; no running deployment's data is.
 *
 * <h2>What this observes, stated exactly</h2>
 *
 * The shipped chain carries DML (V4, V16), but each of those statements runs
 * against a table that is still empty at that point of a fresh chain, so each
 * is a no-op there and neither can witness anything. The writing statement the
 * probe needs is supplied here, as one extra migration in a test-only location
 * layered on top of the real chain. The schema, the policies and the table it
 * writes into are the real ones, produced by the real V1 to V22; only the
 * writing statement is the test's.
 *
 * <h2>Why the migrating role changes shape mid-setup</h2>
 *
 * The shipped chain cannot be applied by a role without BYPASSRLS at all: V6
 * creates {@code kumbuka_ops_reader} WITH BYPASSRLS, and Postgres lets only a
 * role that holds that attribute hand it out. So the chain is applied with the
 * attribute — the privileged shape the deployed migrator has today — and the
 * attribute is then dropped before the probe's own migration runs, which is the
 * de-privileged shape the callback is the backstop for. The role stays the
 * same, and therefore stays the OWNER of the tables: being refused as the owner
 * is {@code FORCE ROW LEVEL SECURITY} at work, and without FORCE the negative
 * case would be green and this whole probe worthless.
 *
 * <h2>Why Flyway is driven directly here</h2>
 *
 * The callback list is build-time configuration, and a running application
 * cannot un-register one. Driving Flyway against a container of this test's own
 * is what makes the negative case reachable at all — and it is also why the
 * third case exists: the first two would read exactly the same on a deployable
 * whose {@code quarkus.flyway.callbacks} line was missing altogether.
 */
class MigrationCallbackWitnessIT {

    private static final String POSTGRES_IMAGE = "postgres:16";
    private static final String MIGRATOR = "witness_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-witness-password";
    private static final String SHIPPED_CHAIN = "classpath:db/migration";
    private static final String WITNESS_CHAIN = "classpath:db/witness";
    private static final String WITNESS_SLUG = "witness-scope";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        // CREATEROLE because V6, V21 and V22 create the consuming service
        // roles. BYPASSRLS only until the shipped chain is applied — see the
        // class javadoc and prepareDatabase below.
        asAdmin("CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE NOSUPERUSER "
            + "BYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The red state: without the callback, the migration carrying DML is
     * refused by the policy.
     *
     * <p>It fails at the policy, and the message says so. That is better than
     * the alternative — writing zero rows and reporting success, which is what
     * the same omission does to an UPDATE — because a migration that succeeds
     * while writing nothing leaves the deployment looking healthy and the data
     * missing.
     */
    @Test
    void without_the_callback_the_dml_migration_is_refused_by_the_policy() throws Exception {
        String url = prepareDatabase("witness_without_callback");

        assertThatThrownBy(() -> applyWitnessMigration(url, false))
            .as("RED STATE, observed: with the callback absent from the configuration, "
                + "app.tenant_id is never bound, the WITH CHECK clause compares the "
                + "incoming row against NULL, and the row cannot be written")
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("row-level security");
    }

    /**
     * The green state: with the callback registered, the same migration applies
     * and the row is there.
     *
     * <p>Both halves are the probe. The red state alone would hold against a
     * migration that is broken for some other reason entirely.
     *
     * <p>The callback is instantiated the way {@code FlywayCallbacksLocator}
     * instantiates it — reflectively, through the no-argument constructor — so
     * this case also witnesses that the class stays reachable that way. A
     * constructor that grew an argument, or an injection point that made the
     * class depend on CDI, would fail here rather than in production.
     */
    @Test
    void with_the_callback_the_same_migration_applies_and_writes_its_row() throws Exception {
        String url = prepareDatabase("witness_with_callback");
        applyWitnessMigration(url, true);

        // Read as the admin: under FORCE the migrating role is subject to its
        // own policies, so it would see nothing here either.
        try (Connection c = DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT slug FROM scope WHERE slug = '" + WITNESS_SLUG + "'")) {
            var found = new ArrayList<String>();
            while (rs.next()) {
                found.add(rs.getString(1));
            }
            assertThat(found)
                .as("and with it registered the write lands — so the refusal above was the "
                    + "missing callback and not a broken migration")
                .containsExactly(WITNESS_SLUG);
        }
    }

    /**
     * The other half of the witness: the application's own configuration names
     * the callback.
     *
     * <p>The two cases above prove what a registered callback DOES. They drive
     * Flyway themselves, so they would prove exactly the same thing about a
     * deployable whose {@code quarkus.flyway.callbacks} line was missing
     * altogether — which is the state this module was in while the callback sat
     * in the source, annotated and unregistered, for two releases.
     *
     * <p>So the registration itself is asserted, and it is asserted against the
     * SHIPPED file rather than against resolved configuration: a test-profile
     * override would otherwise be able to satisfy it, and the claim is about
     * what a deployment boots with.
     */
    @Test
    void the_application_configuration_registers_the_callback() throws IOException {
        Path properties = Files.isRegularFile(Path.of("src/main/resources/application.properties"))
            ? Path.of("src/main/resources/application.properties")
            : Path.of("backend/server/src/main/resources/application.properties");

        String configured = Files.readAllLines(properties).stream()
            .map(String::strip)
            .filter(line -> line.startsWith("quarkus.flyway.callbacks="))
            .findFirst()
            .orElse("");

        assertThat(configured)
            .as("the Quarkus Flyway extension resolves callbacks from this key by class "
                + "name and instantiates them reflectively — it does not discover them as "
                + "CDI beans. A callback that is written, annotated and not named here is "
                + "never registered, with no warning and no error, and every migration "
                + "runs without it")
            .contains(TenantyMigrationCallback.class.getName());
    }

    /**
     * A database of this case's own, owned by the migrating role and carrying
     * the shipped chain — with the role de-privileged afterwards.
     *
     * <p>Each case has to migrate from nothing. Sharing one database would let
     * whichever ran first apply the migration set, and the second would find
     * everything already applied, do nothing, and report success — so the
     * negative case would pass without ever attempting the write it is about.
     */
    private static String prepareDatabase(String name) throws SQLException {
        asAdmin("DROP DATABASE IF EXISTS " + name);
        asAdmin("CREATE DATABASE " + name + " OWNER " + MIGRATOR);
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
            + postgres.getFirstMappedPort() + "/" + name;

        // The shipped chain, under the privileged shape it needs (V6 hands out
        // BYPASSRLS and only a holder may). No callback here: every DML
        // statement in the shipped chain runs against a table that is still
        // empty, so the binding makes no difference to it either way.
        asAdmin("ALTER ROLE " + MIGRATOR + " BYPASSRLS");
        flyway(url, SHIPPED_CHAIN).callbacks(new Callback[] {}).load().migrate();

        // The shape the probe is about, and the shape the next rebuild of the
        // data layer intends: still the owner of every table, no longer able to
        // walk past a policy. New connections see the change; Flyway opens its
        // own below.
        asAdmin("ALTER ROLE " + MIGRATOR + " NOBYPASSRLS");
        return url;
    }

    /**
     * Applies the test-only DML migration on top of the prepared database.
     *
     * @param url          the database of this case
     * @param withCallback whether to register the tenant-binding callback, which
     *                     is the single variable this probe changes
     */
    private static void applyWitnessMigration(String url, boolean withCallback) throws Exception {
        // Both locations, so the applied chain still resolves and Flyway's
        // validation passes; only V900 is pending. The order of the locations
        // does not decide the order of the migrations — the version numbers do,
        // and the fixture is V900 so it can never come between two real ones.
        flyway(url, SHIPPED_CHAIN, WITNESS_CHAIN)
            .callbacks(withCallback
                ? new Callback[] {
                    TenantyMigrationCallback.class.getDeclaredConstructor().newInstance() }
                : new Callback[] {})
            .load()
            .migrate();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(
            String url, String... locations) {
        return Flyway.configure()
            .dataSource(url, MIGRATOR, MIGRATOR_PASSWORD)
            .locations(locations)
            .placeholders(Map.of("tenantId", TenantyMigrationCallback.SINGLETON_TENANT_ID));
    }

    private static void asAdmin(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
