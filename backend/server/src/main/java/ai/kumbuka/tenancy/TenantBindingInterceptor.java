package ai.kumbuka.tenancy;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Sets the Postgres session GUC {@code app.tenant_id} once per
 * transaction the application opens (ADR-0011 §M1).
 *
 * <p>Priority sits one notch above Jakarta's {@code @Transactional}
 * interceptor priority (PLATFORM_BEFORE = 200), so this runs <em>after</em>
 * the transaction is open but <em>before</em> the method body.
 *
 * <p>Idempotent: {@link TenantDatabaseBinding#bindCurrentTransaction()}
 * tracks the bound tenant per transaction and is a no-op on the second
 * call.
 */
@Interceptor
@TenantBound
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class TenantBindingInterceptor {

    @Inject TenantDatabaseBinding binding;

    @AroundInvoke
    public Object invoke(InvocationContext ctx) throws Exception {
        binding.bindCurrentTransaction();
        return ctx.proceed();
    }
}
