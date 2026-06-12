package ai.kumbuka.tenancy;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the tenant-binding root cause fixed in June 2026: the
 * {@code app.tenant_id} GUC must be set INSIDE the open {@code @Transactional}
 * transaction, otherwise RLS hides every row on the web-app console path and
 * scopes/entries come back empty.
 *
 * <p>Unlike {@link CrossTenantIsolationIT} (which drives RLS via raw JDBC under
 * a non-superuser role), this IT proves the interceptor wiring directly by
 * reading the GUC value through the real {@code @TenantBound} interceptor +
 * {@code @Transactional} weave — see {@link TenantGucProbe}. It fails the moment
 * {@link TenantBindingInterceptor}'s {@code @Priority} regresses below the
 * Narayana {@code @Transactional} interceptor.
 *
 * <p>Runs against the DevServices Postgres (real {@code set_config} /
 * {@code current_setting}).
 */
@QuarkusTest
@Tag("integration")
class TenantGucBindingIT {

    /** Singleton tenant seeded by V1__init.sql. */
    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Inject TenantGucProbe probe;
    @Inject TenantContext tenantContext;

    @Test
    void interceptor_sets_app_tenant_id_guc_inside_the_transaction() {
        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            assertThat(probe.readGucInsideTenantBoundTx())
                .as("@TenantBound must set app.tenant_id INSIDE the @Transactional tx — "
                    + "regresses if @Priority drops to PLATFORM_BEFORE+100 (runs before tx opens)")
                .isEqualTo(TENANT_A.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void guc_stays_unset_in_a_transaction_that_is_not_tenant_bound() {
        try (AutoCloseable ignored = tenantContext.bind(TENANT_A)) {
            assertThat(probe.readGucWithoutTenantBound())
                .as("a @Transactional method that is NOT @TenantBound must not bind the GUC — "
                    + "this is why tenant-scoped read resources MUST carry @TenantBound")
                .isNullOrEmpty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
