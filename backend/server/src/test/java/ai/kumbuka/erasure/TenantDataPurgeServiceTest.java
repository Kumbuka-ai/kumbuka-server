package ai.kumbuka.erasure;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-behaviour tests for {@link TenantDataPurgeService} against the
 * DevServices Postgres. Drives the JPQL/native DELETE chain that
 * discharges the OSS-side cleanup at the end of a 30-day tenant purge.
 *
 * <p>Invariants enforced:
 *   • memory must be deleted before scope (FK RESTRICT) — the service
 *     does this in order, so a tenant with memory rows in scope still
 *     purges cleanly (no constraint violation)
 *   • scope_stats is cascaded by Postgres (ON DELETE CASCADE on
 *     scope_stats.scope_id), so we don't need a separate step
 *   • all five counts reflect what was actually removed
 *   • idempotent: re-running on an empty tenant returns all zeros
 */
@QuarkusTest
class TenantDataPurgeServiceTest {

    @Inject TenantDataPurgeService service;
    @Inject EntityManager em;

    /** The V1 seed tenant — matches kumbuka.tenant-id in test/resources/application.properties. */
    private static final String TENANT_LITERAL = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    @Transactional
    void seed() {
        // Wipe project scopes from other tests' fixtures; keep the V1
        // seed (private + global) intact so we can populate against
        // them. Native to bypass @TenantId filter on a clean slate.
        em.createNativeQuery("DELETE FROM memory").executeUpdate();
        em.createNativeQuery("DELETE FROM scope WHERE kind = 'project'").executeUpdate();

        // Seed: one project scope created_by an arbitrary subject, one
        // memory row in private + one in shared, one user_account.
        Scope projectScope = new Scope();
        projectScope.slug = "purge-test-project";
        projectScope.name = "purge-test-project";
        projectScope.kind = ScopeKind.PROJECT;
        projectScope.fixed = false;
        projectScope.archived = false;
        projectScope.createdBy = "purge-test-author";
        projectScope.persist();

        final Scope privateScope = Scope.find("kind = ?1", ScopeKind.PRIVATE).firstResult();
        final Scope globalScope  = Scope.find("kind = ?1", ScopeKind.GLOBAL).firstResult();
        assertThat(privateScope).isNotNull();
        assertThat(globalScope).isNotNull();

        persistMemory(privateScope, "p-1", "secret 1");
        persistMemory(privateScope, "p-2", "secret 2");
        persistMemory(globalScope,  "g-1", "team rule");
        persistMemory(projectScope, "j-1", "project rule");

        // Seed a user_account row (table not mapped as a JPA entity in
        // this module — use native SQL).
        em.createNativeQuery(
            "INSERT INTO user_account (tenant_id, subject, email, role, status, display_name) "
          + "VALUES (CAST(?1 AS uuid), 'purge-test-sub', 'p@x', 'admin', 'active', 'Purge Test') "
          + "ON CONFLICT (tenant_id, subject) DO NOTHING")
            .setParameter(1, TENANT_LITERAL)
            .executeUpdate();
    }

    @Transactional
    void persistMemory(Scope scope, String key, String content) {
        Memory m = new Memory();
        m.ownerSubject = "purge-test-author";
        m.scope = scope;
        m.type = MemoryType.DECISION;
        m.key = key;
        m.content = content;
        m.source = SourceChannel.CONSOLE;
        m.persist();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        // Restore the V1 seed shape so downstream tests see what they
        // expect. The purge tests blow away the singleton tenant; we
        // recreate the minimum (team + private + global scope + the
        // team_settings row + the user_account stub if anything else
        // depends on it).
        em.createNativeQuery("DELETE FROM memory").executeUpdate();
        em.createNativeQuery("DELETE FROM user_account WHERE subject = 'purge-test-sub'").executeUpdate();
        em.createNativeQuery("DELETE FROM scope WHERE kind = 'project'").executeUpdate();

        // Re-create whatever the singleton-tenant tests depend on.
        em.createNativeQuery(
            "INSERT INTO team (id, tenant_id, name) "
          + "VALUES (CAST(?1 AS uuid), CAST(?1 AS uuid), 'Team') ON CONFLICT (id) DO NOTHING")
            .setParameter(1, TENANT_LITERAL)
            .executeUpdate();
        em.createNativeQuery(
            "INSERT INTO scope (tenant_id, name, kind, slug, fixed, archived) "
          + "VALUES (CAST(?1 AS uuid), 'private', 'private', 'private', false, false), "
          + "       (CAST(?1 AS uuid), 'global',  'global',  'global',  true,  false) "
          + "ON CONFLICT (tenant_id, name) DO NOTHING")
            .setParameter(1, TENANT_LITERAL)
            .executeUpdate();
        em.createNativeQuery(
            "INSERT INTO team_settings (tenant_id) VALUES (CAST(?1 AS uuid)) "
          + "ON CONFLICT (tenant_id) DO NOTHING")
            .setParameter(1, TENANT_LITERAL)
            .executeUpdate();
    }

    @Test
    @Transactional
    void purgeRemovesEverythingForTheTenant() {
        TenantDataPurgeService.PurgeResult out = service.purgeTenant(TENANT_LITERAL);

        assertThat(out.memoryDeleted())
            .as("4 seeded memory rows must be deleted")
            .isEqualTo(4);
        assertThat(out.userAccountsDeleted())
            .as("our seeded user_account must be deleted")
            .isGreaterThanOrEqualTo(1);
        assertThat(out.teamSettingsDeleted())
            .as("the singleton team_settings row must be deleted")
            .isEqualTo(1);
        assertThat(out.scopesDeleted())
            .as("private + global + the project scope = 3")
            .isEqualTo(3);
        assertThat(out.teamDeleted())
            .as("the singleton team row must be deleted")
            .isEqualTo(1);

        // Post-conditions: zero rows in every table for this tenant.
        assertThat(Memory.count()).isZero();
        assertThat(Scope.count()).isZero();
        Number remainingUsers = (Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM user_account WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, TENANT_LITERAL)
            .getSingleResult();
        assertThat(remainingUsers.intValue()).isZero();
    }

    @Test
    @Transactional
    void isIdempotent_secondCallReturnsAllZeros() {
        service.purgeTenant(TENANT_LITERAL);
        TenantDataPurgeService.PurgeResult second = service.purgeTenant(TENANT_LITERAL);
        assertThat(second.memoryDeleted()).isZero();
        assertThat(second.userAccountsDeleted()).isZero();
        assertThat(second.teamSettingsDeleted()).isZero();
        assertThat(second.scopesDeleted()).isZero();
        assertThat(second.teamDeleted()).isZero();
    }
}
