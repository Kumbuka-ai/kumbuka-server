package ai.kumbuka.tenancy;

import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cheap structural guard for the {@link TenantBindingInterceptor} priority
 * invariant, alongside the behavioural {@link TenantGucBindingIT}.
 *
 * <p>The interceptor MUST run inside the {@code @Transactional} transaction, so
 * its {@code @Priority} has to be greater than Quarkus Narayana's
 * {@code @Transactional} interceptor priority ({@code PLATFORM_BEFORE + 200}).
 * Smaller priority = invoked first = OUTERMOST = before the tx opens, which is
 * exactly the regression that left the console with empty data.
 */
class TenantBindingInterceptorTest {

    private static final int NARAYANA_TRANSACTIONAL_PRIORITY =
        Interceptor.Priority.PLATFORM_BEFORE + 200;

    @Test
    void priority_runs_inside_the_transactional_interceptor() {
        Priority priority = TenantBindingInterceptor.class.getAnnotation(Priority.class);
        assertThat(priority)
            .as("TenantBindingInterceptor must declare @Priority")
            .isNotNull();
        assertThat(priority.value())
            .as("must be > Narayana @Transactional interceptor (%d) so the app.tenant_id GUC "
                + "binding runs INSIDE the open transaction, not before it",
                NARAYANA_TRANSACTIONAL_PRIORITY)
            .isGreaterThan(NARAYANA_TRANSACTIONAL_PRIORITY);
    }
}
