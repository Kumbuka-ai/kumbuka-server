package ai.kumbuka.service;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.TeamSettingsRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the D3 fallback contract: stored settings stay intact even
 * when the defaultScope drifts (archived / missing); the *effective*
 * policy reported at runtime degrades to ASK.
 */
@QuarkusTest
class WritePolicyResolverTest {

    @Inject WritePolicyResolver resolver;
    @Inject TeamSettingsRepository settings;
    @Inject ScopeRepository scopes;

    @Test
    @Transactional
    void ask_passes_through() {
        settings.updatePolicy(WritePolicy.ASK, null);

        var r = resolver.resolve();
        assertThat(r.stored()).isEqualTo(WritePolicy.ASK);
        assertThat(r.effective()).isEqualTo(WritePolicy.ASK);
        assertThat(r.defaultScopeStatus()).isEqualTo(WritePolicyResolver.DefaultScopeStatus.OK);
    }

    @Test
    @Transactional
    void global_passes_through() {
        settings.updatePolicy(WritePolicy.GLOBAL, null);

        var r = resolver.resolve();
        assertThat(r.effective()).isEqualTo(WritePolicy.GLOBAL);
    }

    @Test
    @Transactional
    void project_with_valid_scope_passes_through() {
        Scope s = scopes.createProject("alpha", "Project Alpha", null, "u-test");
        settings.updatePolicy(WritePolicy.PROJECT, s.id);

        var r = resolver.resolve();
        assertThat(r.stored()).isEqualTo(WritePolicy.PROJECT);
        assertThat(r.effective()).isEqualTo(WritePolicy.PROJECT);
        assertThat(r.defaultScopeSlug()).isEqualTo("alpha");
        assertThat(r.defaultScopeStatus()).isEqualTo(WritePolicyResolver.DefaultScopeStatus.OK);
    }

    @Test
    @Transactional
    void project_with_missing_default_falls_back_to_ask() {
        settings.updatePolicy(WritePolicy.PROJECT, null);

        var r = resolver.resolve();
        assertThat(r.stored()).isEqualTo(WritePolicy.PROJECT);   // stored stays PROJECT
        assertThat(r.effective()).isEqualTo(WritePolicy.ASK);    // effective falls back
        assertThat(r.defaultScopeStatus()).isEqualTo(WritePolicyResolver.DefaultScopeStatus.MISSING);
    }

    @Test
    @Transactional
    void project_with_archived_default_falls_back_to_ask() {
        Scope s = scopes.createProject("beta", "Project Beta", null, "u-test");
        settings.updatePolicy(WritePolicy.PROJECT, s.id);
        scopes.archive("beta");

        var r = resolver.resolve();
        assertThat(r.effective()).isEqualTo(WritePolicy.ASK);
        assertThat(r.defaultScopeStatus()).isEqualTo(WritePolicyResolver.DefaultScopeStatus.ARCHIVED);
        assertThat(r.defaultScopeSlug()).isEqualTo("beta");

        // Stored row was NOT mutated by the read.
        TeamSettings stored = settings.current();
        assertThat(stored.getWritePolicy()).isEqualTo(WritePolicy.PROJECT);
        assertThat(stored.defaultScopeId).isEqualTo(s.id);
    }
}
