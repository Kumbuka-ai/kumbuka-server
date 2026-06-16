package ai.kumbuka.admin;

import ai.kumbuka.tenancy.TenantBound;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the second half of the June-2026 tenant-binding fix:
 * the tenant-scoped admin REST resources must be {@code @Transactional} (so a
 * transaction opens and the {@code app.tenant_id} GUC can be set) AND
 * {@code @TenantBound} (so the GUC-binding interceptor actually fires).
 *
 * <p>The GET read endpoints originally lacked {@code @Transactional}, so reads
 * ran with no transaction, the GUC was never set, and RLS hid the rows. This
 * test fails the moment either annotation is dropped from one of these classes.
 */
class AdminReadResourceContractTest {

    private static final List<Class<?>> TENANT_SCOPED_RESOURCES = List.of(
        AdminScopesResource.class,
        AdminEntriesResource.class,
        AdminSettingsResource.class,
        // S018: me()/updateMe read+write the caller's user_account; without a tx
        // the app.tenant_id GUC is unset and RLS hides the row (rendered the sub).
        SessionResource.class);

    @Test
    void tenant_scoped_resources_are_transactional_and_tenant_bound() {
        for (Class<?> resource : TENANT_SCOPED_RESOURCES) {
            assertThat(resource.isAnnotationPresent(Transactional.class))
                .as("%s must be @Transactional so reads open a tx and the app.tenant_id GUC is set",
                    resource.getSimpleName())
                .isTrue();
            assertThat(resource.isAnnotationPresent(TenantBound.class))
                .as("%s must be @TenantBound so the GUC-binding interceptor fires",
                    resource.getSimpleName())
                .isTrue();
        }
    }
}
