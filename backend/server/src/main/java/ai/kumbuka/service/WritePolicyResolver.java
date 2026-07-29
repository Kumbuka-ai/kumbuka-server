package ai.kumbuka.service;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.TeamSettingsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the team's write policy at request time.
 *
 * If the stored {@code writePolicy} is PROJECT but the configured
 * {@code default_scope_id} is missing or points to an archived/private/
 * fixed scope, this resolver reports the effective policy as ASK without
 * mutating the stored row. The admin UI surfaces the drift so the operator
 * can choose a valid scope.
 *
 * If the stored policy is GLOBAL or ASK, the resolver returns it verbatim.
 */
@ApplicationScoped
public class WritePolicyResolver {

    @Inject TeamSettingsRepository settings;
    @Inject ScopeRepository scopes;

    public enum DefaultScopeStatus { OK, MISSING, ARCHIVED, INVALID }

    public record Resolved(
        WritePolicy stored,
        WritePolicy effective,
        DefaultScopeStatus defaultScopeStatus,
        String defaultScopeSlug
    ) {}

    public Resolved resolve() {
        TeamSettings s = settings.current();
        WritePolicy stored = s.getWritePolicy();

        if (stored != WritePolicy.PROJECT) {
            return new Resolved(stored, stored, DefaultScopeStatus.OK, null);
        }

        // PROJECT requires a usable default scope.
        if (s.defaultScopeId == null) {
            return new Resolved(stored, WritePolicy.ASK, DefaultScopeStatus.MISSING, null);
        }
        Optional<Scope> sc = scopes.findByIdOptional(s.defaultScopeId);
        if (sc.isEmpty()) {
            return new Resolved(stored, WritePolicy.ASK, DefaultScopeStatus.MISSING, null);
        }
        Scope scope = sc.get();
        if (Boolean.TRUE.equals(scope.archived)) {
            return new Resolved(stored, WritePolicy.ASK, DefaultScopeStatus.ARCHIVED, scope.slug);
        }
        // Defensive: a non-project scope can't be the default. Treat as drift.
        if (scope.kind != ai.kumbuka.domain.ScopeKind.PROJECT) {
            return new Resolved(stored, WritePolicy.ASK, DefaultScopeStatus.INVALID, scope.slug);
        }
        return new Resolved(stored, WritePolicy.PROJECT, DefaultScopeStatus.OK, scope.slug);
    }
}
