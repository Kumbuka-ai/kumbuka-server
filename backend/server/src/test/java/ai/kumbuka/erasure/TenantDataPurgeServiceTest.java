package ai.kumbuka.erasure;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-behaviour tests for {@link TenantDataPurgeService} against the
 * DevServices Postgres. Drives the JPQL/native DELETE chain that
 * discharges the OSS-side cleanup at the end of a 30-day tenant purge.
 *
 * <p>Invariants enforced:
 *   • scope_stats is cascaded by Postgres (ON DELETE CASCADE on
 *     scope_stats.scope_id), so we don't need a separate step
 *   • all four counts reflect what was actually removed
 *   • idempotent: re-running on an empty tenant returns all zeros
 *   • <strong>the entry step is gone</strong>, and gone in the only way that
 *     can be observed from outside: with an entry row still on disk, the
 *     {@code scope} delete is REFUSED by {@code memory.scope_id … ON DELETE
 *     RESTRICT}. That refusal is the witness. Put {@code Memory.deleteAll()}
 *     back at the top of the service and this test goes green-by-accident —
 *     which is exactly what it is here to catch.
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
        // user_account. No entry rows — the core does not own that table.
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

        // Seed a user_account row (table not mapped as a JPA entity in
        // this module — use native SQL).
        em.createNativeQuery(
            "INSERT INTO user_account (tenant_id, subject, email, role, status, display_name) "
          + "VALUES (CAST(?1 AS uuid), 'purge-test-sub', 'p@x', 'admin', 'active', 'Purge Test') "
          + "ON CONFLICT (tenant_id, subject) DO NOTHING")
            .setParameter(1, TENANT_LITERAL)
            .executeUpdate();
    }

    /**
     * Plant one row in the dead {@code memory} table, against the global scope,
     * by native SQL. The core has no entity for it any more — that is the point
     * — so the statement names the columns the chain leaves NOT NULL without a
     * default.
     */
    @Transactional
    void plantEntryRowOnGlobalScope() {
        em.createNativeQuery(
            "INSERT INTO memory (tenant_id, owner_subject, scope_id, type, content, "
          + "                    logical_id, is_private, source) "
          + "SELECT CAST(?1 AS uuid), 'purge-test-author', s.id, 'decision', 'still here', "
          + "       gen_random_uuid(), false, 'console' "
          + "  FROM scope s WHERE s.tenant_id = CAST(?1 AS uuid) AND s.kind = 'global'")
            .setParameter(1, TENANT_LITERAL)
            .executeUpdate();
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
            "INSERT INTO team (id, tenant_id, name, alias) "
          + "VALUES (CAST(?1 AS uuid), CAST(?1 AS uuid), 'Team', 'default') ON CONFLICT (id) DO NOTHING")
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
    void purgeRemovesEverythingTheCoreOwnsForTheTenant() {
        TenantDataPurgeService.PurgeResult out = service.purgeTenant(TENANT_LITERAL);

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
        assertThat(second.userAccountsDeleted()).isZero();
        assertThat(second.teamSettingsDeleted()).isZero();
        assertThat(second.scopesDeleted()).isZero();
        assertThat(second.teamDeleted()).isZero();
    }

    /**
     * The witness for the removed entry step. Not annotated {@code @Transactional}:
     * the constraint violation must land inside the service's own transaction,
     * the way it would in production, instead of poisoning the test's.
     */
    @Test
    void refusesToDropScopesWhileEntriesStillReferenceThem() {
        plantEntryRowOnGlobalScope();

        assertThatThrownBy(() -> service.purgeTenant(TENANT_LITERAL))
            .as("with an entry row on disk the scope delete must hit "
              + "memory.scope_id ON DELETE RESTRICT — the core no longer clears it first")
            .hasRootCauseInstanceOf(java.sql.SQLException.class);

        Number left = (Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM memory WHERE tenant_id = CAST(?1 AS uuid)")
            .setParameter(1, TENANT_LITERAL)
            .getSingleResult();
        assertThat(left.intValue())
            .as("the planted entry is untouched: this service does not speak for that table")
            .isEqualTo(1);
    }

    /**
     * The entry count left with the memory engine. A {@code memoryDeleted}
     * reporting {@code 0} would assert an empty store; absence says only that
     * this service does not count it, which is the true statement.
     */
    @Test
    void resultCarriesNoEntryCount() {
        assertThat(Arrays.stream(TenantDataPurgeService.PurgeResult.class.getRecordComponents())
                .map(RecordComponent::getName))
            .containsExactly(
                "userAccountsDeleted", "teamSettingsDeleted", "scopesDeleted", "teamDeleted");
    }
}
