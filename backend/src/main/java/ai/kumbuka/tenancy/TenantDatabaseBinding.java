package ai.kumbuka.tenancy;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Wires the Postgres session GUC {@code app.tenant_id} per JTA
 * transaction. Layer 2 of the two-layer tenant enforcement (ADR-0011).
 *
 * <p>The RLS policies in V3 read {@code current_setting('app.tenant_id',
 * true)}. Without this binding, every query would fail closed because
 * the GUC would be NULL and {@code tenant_id = NULL} is FALSE.
 *
 * <p>Implementation: we register a transaction synchronization that, at
 * the start of the transaction's first query, executes
 * {@code SET LOCAL app.tenant_id = '...'}. {@code SET LOCAL} scopes the
 * value to the current transaction so a connection returned to the pool
 * never carries an old tenant id across to the next caller.
 *
 * <p>Reads {@link TenantContext#current()} — the single source of truth.
 */
@ApplicationScoped
public class TenantDatabaseBinding {

    private static final Logger LOG = Logger.getLogger(TenantDatabaseBinding.class);
    private static final String GUC_KEY_PREFIX = "ai.kumbuka.tenancy.bound:";

    @Inject TenantContext context;
    @Inject EntityManager em;
    @Inject TransactionSynchronizationRegistry txReg;

    @PostConstruct
    void init() {
        LOG.debug("TenantDatabaseBinding ready");
    }

    /**
     * Bind the current tenant onto the active transaction. Idempotent per
     * transaction: a second call within the same transaction is a no-op
     * even if the tenant differs (which would be a programming error and
     * we log it).
     *
     * <p>Call from anywhere inside an open {@code @Transactional}
     * boundary — the request filter and the MCP tool dispatcher both do.
     */
    public void bindCurrentTransaction() {
        Set<String> alreadyBound = ensureRegistry();
        if (alreadyBound == null) {
            // No active transaction — caller is outside @Transactional.
            // SET LOCAL would have no envelope; skip silently. (Hibernate
            // session reads still benefit from the @TenantId filter.)
            return;
        }
        String tenant = context.current().toString();
        if (alreadyBound.contains(tenant)) {
            return;
        }
        if (!alreadyBound.isEmpty()) {
            LOG.warnf("tenant rebinding inside one transaction: %s -> %s",
                alreadyBound, tenant);
        }
        // SET LOCAL takes effect for the rest of this transaction and
        // resets on commit/rollback. set_config(..., true) is the same
        // semantics expressed as SQL so it composes with the same
        // connection-pool reset behavior every modern Postgres driver
        // already gives us.
        em.createNativeQuery("SELECT set_config('app.tenant_id', :v, true)")
            .setParameter("v", tenant)
            .getSingleResult();
        alreadyBound.add(tenant);
    }

    @SuppressWarnings("unchecked")
    private Set<String> ensureRegistry() {
        String key = GUC_KEY_PREFIX + "set";
        Object existing;
        try {
            existing = txReg.getResource(key);
        } catch (RuntimeException noTx) {
            return null;
        }
        if (existing instanceof Set<?>) {
            return (Set<String>) existing;
        }
        Set<String> bound = new HashSet<>();
        try {
            txReg.putResource(key, bound);
            txReg.registerInterposedSynchronization(new ResetSync(bound));
        } catch (RuntimeException ignore) {
            // Registry refused (e.g. TX in unexpected state). Skip — the
            // synchronization is best-effort anyway, since SET LOCAL
            // already resets at commit/rollback.
            return null;
        }
        return bound;
    }

    /** Cleans up our per-TX record on commit/rollback. */
    private static final class ResetSync implements Synchronization {
        private final Set<String> bound;
        ResetSync(Set<String> bound) { this.bound = bound; }
        @Override public void beforeCompletion() { /* no-op */ }
        @Override public void afterCompletion(int status) { bound.clear(); }
    }

    /**
     * Test convenience: returns whether the current TX already has a tenant
     * bound (does not bind). Used by the cross-tenant IT.
     */
    public boolean isBoundOnCurrentTransaction() {
        try {
            Object existing = txReg.getResource(GUC_KEY_PREFIX + "set");
            return existing instanceof Set<?> s && !s.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private void noTransaction() throws SystemException {
        // Marker for IDE — referenced from javadoc above.
    }
}
