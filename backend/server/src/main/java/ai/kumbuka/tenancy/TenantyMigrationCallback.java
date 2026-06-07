package ai.kumbuka.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Flyway callback that fires before every migration runs. Sets the
 * Postgres session GUC <code>app.tenant_id</code> to the singleton
 * tenant so that any DML inside a migration (seed data, backfills)
 * passes the RLS policies introduced in V3.
 *
 * V3 itself is pure DDL and would not need this; the callback is here
 * for migrations that <em>do</em> carry DML in the future. Without it,
 * a forgotten {@code set_config} inside a seed migration would silently
 * fail closed under <code>FORCE ROW LEVEL SECURITY</code>.
 *
 * <p>Discovery: registered as a CDI bean and picked up automatically
 * by {@code quarkus-flyway} via the
 * {@link org.flywaydb.core.api.callback.Callback} SPI.
 */
@ApplicationScoped
public class TenantyMigrationCallback extends BaseCallback {

    /**
     * Singleton tenant id, matches the seed row in V1__init.sql. Kept as
     * a string constant rather than reading {@link ai.kumbuka.config.MemoryConfig}
     * — this callback runs before CDI is fully available during boot.
     */
    static final String SINGLETON_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public void handle(Event event, Context context) {
        try (Statement st = context.getConnection().createStatement()) {
            // set_config(..., is_local=true) limits the binding to the
            // current transaction — the same envelope Flyway runs the
            // migration in.
            st.execute("SELECT set_config('app.tenant_id', '"
                + SINGLETON_TENANT_ID + "', true)");
        } catch (SQLException e) {
            throw new IllegalStateException(
                "failed to set app.tenant_id GUC before Flyway migration", e);
        }
    }
}
