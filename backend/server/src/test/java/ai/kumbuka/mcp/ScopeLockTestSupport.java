package ai.kumbuka.mcp;

import ai.kumbuka.domain.GovernanceAudit;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Test-only helper for the scope-lock integration tests. Seeds scopes
 * (open / locked), plants entries, and reads governance-audit rows — all inside
 * the tenant-bound transaction (the {@code app.tenant_id} GUC must be set, which
 * {@code @TenantBound} does), resolving the same default test tenant the tools
 * resolve. Bypasses the surface guards on purpose: it sets up state through the
 * repositories, the enforcement is exercised by the surfaces under test.
 */
@ApplicationScoped
@TenantBound
public class ScopeLockTestSupport {

    @Inject ScopeRepository scopes;
    @Inject MemoryRepository memories;
    @Inject EntityManager em;

    /** Idempotently ensure a PROJECT scope exists with the given lock state. */
    @Transactional
    public void ensureProject(String slug, boolean locked) {
        if (scopes.findBySlug(slug).isEmpty()) {
            scopes.createProject(slug, slug, null, "scope-lock-it-seed");
        }
        scopes.setLocked(slug, locked);
    }

    /** Seed an ordinary (unlocked) entry into an OPEN project scope, then lock
     *  the scope — so the locked scope holds a real row for forget/override tests. */
    @Transactional
    public UUID seedEntryThenLock(String slug, String key, String content) {
        ensureProject(slug, false);
        Memory m = memories.createShared(
            "scope-lock-it-author", slug, MemoryType.DECISION, key, content,
            ai.kumbuka.domain.SourceChannel.CONSOLE);
        scopes.setLocked(slug, true);
        return m.logicalId;
    }

    /** Seed a system-locked entry into a project scope, then lock the scope —
     *  for the axis-composition test (entry lock + scope lock). The row is
     *  written through the server-derived system channel, the only writer that
     *  produces {@code lock = 'system'}. */
    @Transactional
    public UUID seedSystemEntryThenLock(String slug, String key, String content) {
        ensureProject(slug, false);
        // Plant the system-locked entry BELOW the write seam (direct persist): the
        // system channel no longer persists through the repository (it fails loud
        // there), so a lock='system' row is created directly, as a legacy seed row
        // would sit in the table. onCreate still requires source=SYSTEM + sentinel.
        Memory m = new Memory();
        m.ownerSubject = ai.kumbuka.domain.SystemSubject.SENTINEL;
        m.scope = scopes.requireBySlug(slug);
        m.type = MemoryType.DECISION;
        m.key = key;
        m.content = content;
        m.source = ai.kumbuka.domain.SourceChannel.SYSTEM;
        m.lock = ai.kumbuka.domain.MemoryLock.SYSTEM;
        memories.persist(m);
        scopes.setLocked(slug, true);
        return m.logicalId;
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

    @Transactional
    public long entryCount(String slug) {
        return memories.count("scope.slug = ?1", slug);
    }

    /** Governance-audit rows for an action, newest first — read content-free. */
    @Transactional
    public List<GovernanceAudit> auditRows(String action) {
        return GovernanceAudit.list("action = ?1 order by createdAt desc", action);
    }

    /**
     * Remove everything these tests planted so the shared DevServices Postgres is
     * left clean for the count-sensitive isolation ITs (CrossTenantIsolationIT,
     * ScopeStatsRefresherIT). System-locked rows delete through the ordinary path
     * (there is no delete-block below the app layer). Restores {@code global} to
     * unlocked. Leaves the (now-empty) {@code sl-*} test scopes — harmless, and
     * avoids FK churn with {@code scope_stats}.
     */
    @Transactional
    public void cleanup() {
        em.createNativeQuery(
            "DELETE FROM memory WHERE scope_id IN "
            + "(SELECT id FROM scope WHERE slug LIKE 'sl-%')").executeUpdate();
        em.createNativeQuery(
            "DELETE FROM memory WHERE key = 'p-key' AND scope_id IN "
            + "(SELECT id FROM scope WHERE slug = 'private')").executeUpdate();
        scopes.setLocked("global", false);
    }
}
