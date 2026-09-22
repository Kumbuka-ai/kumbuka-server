package ai.kumbuka.admin;

import ai.kumbuka.domain.GovernanceAudit;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Test-only helper for the scope-lock integration tests. Seeds scopes
 * (open / locked) and reads governance-audit rows — all inside the tenant-bound
 * transaction (the {@code app.tenant_id} GUC must be set, which
 * {@code @TenantBound} does), resolving the same default test tenant the
 * requests resolve. Bypasses the surface guards on purpose: it sets up state
 * through the repository, the enforcement is exercised by the surface under
 * test.
 *
 * <p>It used to plant entries too, and lived in the {@code mcp} package beside
 * the wire-level enforcement test. Both went with the memory engine; what the
 * core still owns is the lock flag on a scope and the governance event that
 * records a flip.
 */
@ApplicationScoped
@TenantBound
public class ScopeLockTestSupport {

    @Inject ScopeRepository scopes;

    /** Idempotently ensure a PROJECT scope exists with the given lock state. */
    @Transactional
    public void ensureProject(String slug, boolean locked) {
        if (scopes.findBySlug(slug).isEmpty()) {
            scopes.createProject(slug, slug, null, "scope-lock-it-seed");
        }
        scopes.setLocked(slug, locked);
    }

    @Transactional
    public boolean isLocked(String slug) {
        Scope s = scopes.requireBySlug(slug);
        return Boolean.TRUE.equals(s.locked);
    }

    @Transactional
    public void setLocked(String slug, boolean locked) {
        scopes.setLocked(slug, locked);
    }

    /** Governance-audit rows for an action, newest first — read content-free. */
    @Transactional
    public List<GovernanceAudit> auditRows(String action) {
        return GovernanceAudit.list("action = ?1 order by createdAt desc", action);
    }

    /**
     * Restore {@code global} to unlocked so the count-sensitive isolation ITs
     * see the shape they expect. The {@code sl-*} project scopes are left in
     * place — harmless, and it avoids FK churn with {@code scope_stats}.
     */
    @Transactional
    public void cleanup() {
        scopes.setLocked("global", false);
    }
}
