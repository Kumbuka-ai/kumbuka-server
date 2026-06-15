package ai.kumbuka.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the D-CORE-12 boot-guard. Exercised as a {@link QuarkusTest} so the
 * branch coverage lands in jacoco-quarkus.exec (a plain JUnit test would run
 * outside the Quarkus classloader and contribute zero coverage).
 *
 * Real invariants the test enforces:
 *   • mcp tenant disabled → guard is a no-op (data-layer test boot path)
 *   • token.principal-claim=sub → passes (the corrected, effective pin)
 *   • legacy un-segmented key present → aborts (the regression tripwire)
 *   • correct key missing or != sub → aborts (pin not effective)
 *   • the real test-profile config boots the observer without throwing
 */
@QuarkusTest
class OidcMcpPrincipalClaimGuardTest {

    @Inject
    OidcMcpPrincipalClaimGuard guard;

    @Inject
    Config realConfig;

    /** Mock with the enabled-tenant defaults: no legacy key, correct key absent. */
    private Config enabledConfig() {
        Config c = mock(Config.class);
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.TENANT_ENABLED_KEY, Boolean.class))
            .thenReturn(Optional.of(true));
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.LEGACY_KEY, String.class))
            .thenReturn(Optional.empty());
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.CORRECT_KEY, String.class))
            .thenReturn(Optional.empty());
        return c;
    }

    @Test
    void passesWhenCorrectKeyIsSub() {
        Config c = enabledConfig();
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.CORRECT_KEY, String.class))
            .thenReturn(Optional.of("sub"));
        assertThatCode(() -> OidcMcpPrincipalClaimGuard.verify(c)).doesNotThrowAnyException();
    }

    @Test
    void tripwireFiresOnLegacyMisPath() {
        Config c = enabledConfig();
        // Even with the correct key also present, a stray legacy key is a defect signal.
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.LEGACY_KEY, String.class))
            .thenReturn(Optional.of("sub"));
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.CORRECT_KEY, String.class))
            .thenReturn(Optional.of("sub"));
        assertThatThrownBy(() -> OidcMcpPrincipalClaimGuard.verify(c))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(OidcMcpPrincipalClaimGuard.CORRECT_KEY);
    }

    @Test
    void abortsWhenCorrectKeyMissing() {
        assertThatThrownBy(() -> OidcMcpPrincipalClaimGuard.verify(enabledConfig()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0008");
    }

    @Test
    void abortsWhenCorrectKeyNotSub() {
        Config c = enabledConfig();
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.CORRECT_KEY, String.class))
            .thenReturn(Optional.of("preferred_username"));
        assertThatThrownBy(() -> OidcMcpPrincipalClaimGuard.verify(c))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noOpWhenTenantDisabled() {
        Config c = enabledConfig();
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.TENANT_ENABLED_KEY, Boolean.class))
            .thenReturn(Optional.of(false));
        // Legacy key present + correct missing, but tenant off → no enforcement.
        when(c.getOptionalValue(OidcMcpPrincipalClaimGuard.LEGACY_KEY, String.class))
            .thenReturn(Optional.of("sub"));
        assertThatCode(() -> OidcMcpPrincipalClaimGuard.verify(c)).doesNotThrowAnyException();
    }

    @Test
    void observerRunsAgainstRealConfigWithoutThrowing() {
        // The test profile disables the mcp tenant, so onStart exercises the
        // observer wiring + the disabled-branch no-op against the real config.
        assertThatCode(() -> guard.onStart(null)).doesNotThrowAnyException();
    }
}
