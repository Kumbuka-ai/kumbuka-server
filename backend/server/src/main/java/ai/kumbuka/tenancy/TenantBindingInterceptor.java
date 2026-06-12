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
 * <p>Priority must be HIGHER than Jakarta's {@code @Transactional}
 * interceptor (Quarkus Narayana = {@code PLATFORM_BEFORE + 200}) so this runs
 * <em>inside</em> the open transaction. CDI invokes smaller-priority
 * interceptors first (outermost): at {@code +100} this ran <em>before</em>
 * {@code @Transactional} opened the transaction, so
 * {@link TenantDatabaseBinding#bindCurrentTransaction()} found no active
 * transaction and silently skipped the {@code app.tenant_id} GUC — RLS then
 * hid every row on the web-app console path (empty data). {@code +300} puts it
 * after the transaction starts but still before the method body.
 *
 * <p>Idempotent: {@link TenantDatabaseBinding#bindCurrentTransaction()}
 * tracks the bound tenant per transaction and is a no-op on the second
 * call.
 */
@Interceptor
@TenantBound
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 300)
public class TenantBindingInterceptor {

    @Inject TenantDatabaseBinding binding;

    @AroundInvoke
    public Object invoke(InvocationContext ctx) throws Exception {
        binding.bindCurrentTransaction();
        return ctx.proceed();
    }
}
