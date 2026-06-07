package ai.kumbuka.tenancy;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Pins the per-request tenant for the duration of the request and
 * unbinds it after the response, even on exception (M6).
 *
 * <p>Both pipelines flow through this filter: the BFF {@code /api/*}
 * routes and the bearer-protected {@code /mcp} route. In OSS the
 * resolver always returns the singleton tenant; the commercial edition
 * supplies a request-aware resolver.
 *
 * <p>Runs at {@link Priorities#AUTHENTICATION} + 100 so authentication
 * filters establish the security identity first; the tenant is then
 * resolved against that identity if the commercial resolver wants it.
 *
 * <p>The Postgres GUC isn't set here — it sits inside the JTA
 * transaction, which opens later (per-`@Transactional` method).
 * {@link TenantDatabaseBinding#bindCurrentTransaction()} is called from
 * each {@code @Transactional} entry point that needs DB access.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 100)
public class TenantRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String CONTEXT_HANDLE = "ai.kumbuka.tenancy.bound-handle";

    @Inject TenantContext context;
    @Inject TenantResolver resolver;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        UUID tenant = resolver.currentTenant();
        AutoCloseable handle = context.bind(tenant);
        requestContext.setProperty(CONTEXT_HANDLE, handle);
    }

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        AutoCloseable handle = (AutoCloseable) requestContext.getProperty(CONTEXT_HANDLE);
        if (handle == null) return;
        try {
            handle.close();
        } catch (Exception e) {
            throw new RuntimeException("tenant unbind failed", e);
        } finally {
            requestContext.removeProperty(CONTEXT_HANDLE);
        }
    }
}
