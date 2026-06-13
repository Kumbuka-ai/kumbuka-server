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

    /**
     * Bind a tenant DISTINCT from the V1 singleton (00…001). The interceptor
     * resolves the GUC from {@link TenantContext}, so reading back exactly this
     * value proves the {@code SET LOCAL} ran inside the tx — a stale/ambient GUC
     * left on a pooled connection would carry a different (or no) value and the
     * assertion would fail, which is what makes this test discriminating against
     * the {@code @Priority} regression.
     */
    static final UUID BOUND_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Inject TenantGucProbe probe;
    @Inject TenantContext tenantContext;

    @Test
    void interceptor_sets_app_tenant_id_guc_inside_the_transaction() {
        try (AutoCloseable ignored = tenantContext.bind(BOUND_TENANT)) {
            assertThat(probe.readGucInsideTenantBoundTx())
                .as("@TenantBound must set app.tenant_id to the bound tenant INSIDE the "
                    + "@Transactional tx — regresses if @Priority drops to PLATFORM_BEFORE+100 "
                    + "(the interceptor would run before the tx opens and the SET LOCAL is skipped)")
                .isEqualTo(BOUND_TENANT.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
