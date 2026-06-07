package ai.kumbuka.repo;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.TeamSettings.CreateScopes;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Repository for the singleton-per-tenant {@link TeamSettings} row.
 * The row is seeded by V2 migration; this repo only reads + updates.
 */
@Transactional
@TenantBound
@ApplicationScoped
public class TeamSettingsRepository implements PanacheRepositoryBase<TeamSettings, UUID> {

    @Inject MemoryConfig config;

    public TeamSettings current() {
        // Singleton per tenant; Hibernate's @TenantId filter narrows the
        // query to the current tenant automatically (ADR-0011). The
        // surrogate PK (V4) made `findById` tenant-agnostic, so we go via
        // `find().firstResult()`.
        TeamSettings s = findAll().firstResult();
        if (s == null) {
            throw new IllegalStateException(
                "team_settings row missing for current tenant — "
                + "V2 migration should have seeded it");
        }
        return s;
    }

    @Transactional
    public TeamSettings updatePolicy(WritePolicy writePolicy, UUID defaultScopeId) {
        TeamSettings s = current();
        s.setWritePolicy(writePolicy);
        s.defaultScopeId = defaultScopeId;
        return s;
    }

    @Transactional
    public TeamSettings updateCreateScopes(CreateScopes createScopes) {
        TeamSettings s = current();
        s.setCreateScopes(createScopes);
        return s;
    }
}
