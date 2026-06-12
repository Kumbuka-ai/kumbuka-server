package ai.kumbuka.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Test-only bean that reads the Postgres session GUC {@code app.tenant_id}
 * from inside (and outside) a {@code @TenantBound} transaction, so
 * {@link TenantGucBindingIT} can prove the GUC is actually set INSIDE the
 * open transaction.
 *
 * <p>This is the mechanism behind the June-2026 "console shows empty data"
 * marathon: when {@link TenantBindingInterceptor} ran at
 * {@code PLATFORM_BEFORE + 100} it fired BEFORE {@code @Transactional} opened
 * the transaction, so the GUC was never set and RLS hid every row. Reading the
 * GUC via the same {@link EntityManager} (hence the same tx-bound connection)
 * is the precise probe — under the DevServices superuser datasource RLS is
 * bypassed, so a row-count assertion would NOT catch the regression, but the
 * GUC value itself still tells the truth.
 */
@ApplicationScoped
public class TenantGucProbe {

    @Inject EntityManager em;

    /** Read the GUC inside a {@code @TenantBound @Transactional} method — the
     *  interceptor must have set it before the body runs. */
    @TenantBound
    @Transactional
    public String readGucInsideTenantBoundTx() {
        return currentTenantGuc();
    }

    /** Read the GUC inside a transaction that is NOT {@code @TenantBound} — the
     *  interceptor never fires, so the GUC must be unset. This documents why
     *  tenant-scoped resources need {@code @TenantBound}, not just
     *  {@code @Transactional}. */
    @Transactional
    public String readGucWithoutTenantBound() {
        return currentTenantGuc();
    }

    private String currentTenantGuc() {
        Object value = em.createNativeQuery("SELECT current_setting('app.tenant_id', true)")
            .getSingleResult();
        return value == null ? null : value.toString();
    }
}
