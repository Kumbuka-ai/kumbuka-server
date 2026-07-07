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
 * Pins the D-CORE-12 boot-guard across the guarded tenants ({@code mcp},
 * {@code admin}). Exercised as a {@link QuarkusTest} so the branch coverage
 * lands in jacoco-quarkus.exec (a plain JUnit test would run outside the
 * Quarkus classloader and contribute zero coverage).
 *
 * Real invariants the test enforces, per tenant:
 *   • tenant disabled → guard is a no-op (data-layer test boot path)
 *   • token.principal-claim=sub → passes (the corrected, effective pin)
 *   • legacy un-segmented key present → aborts (the regression tripwire)
 *   • correct key missing or != sub → aborts (pin not effective)
 *   • the real test-profile config boots the observer without throwing
 */
@QuarkusTest
class OidcPrincipalClaimGuardTest {

    @Inject
    OidcPrincipalClaimGuard guard;

    /** Mock with one tenant enabled and its keys at the given state. */
    private Config configFor(String tenant, String legacy, String correct) {
        Config c = mock(Config.class);
        when(c.getOptionalValue("quarkus.oidc." + tenant + ".tenant-enabled", Boolean.class))
            .thenReturn(Optional.of(true));
        when(c.getOptionalValue("quarkus.oidc." + tenant + ".principal-claim", String.class))
            .thenReturn(Optional.ofNullable(legacy));
        when(c.getOptionalValue("quarkus.oidc." + tenant + ".token.principal-claim", String.class))
            .thenReturn(Optional.ofNullable(correct));
        return c;
    }

    /** Mock for the mcp-audience assertion: mcp tenant enablement + audience value. */
    private Config audienceConfig(Boolean mcpEnabled, String audience) {
        Config c = mock(Config.class);
        when(c.getOptionalValue("quarkus.oidc.mcp.tenant-enabled", Boolean.class))
            .thenReturn(Optional.ofNullable(mcpEnabled));
        when(c.getOptionalValue("quarkus.oidc.mcp.token.audience", String.class))
            .thenReturn(Optional.ofNullable(audience));
        return c;
    }

    @Test
    void passesWhenCorrectKeyIsSub() {
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("admin", null, "sub"), "admin"))
            .doesNotThrowAnyException();
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("mcp", null, "sub"), "mcp"))
            .doesNotThrowAnyException();
    }

    @Test
    void tripwireFiresOnLegacyMisPath() {
        // Even with the correct key also present, a stray legacy key is a defect signal.
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("admin", "sub", "sub"), "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("quarkus.oidc.admin.token.principal-claim");
    }

    @Test
    void abortsWhenCorrectKeyMissing() {
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("mcp", null, null), "mcp"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0008");
    }

    @Test
    void abortsWhenCorrectKeyNotSub() {
        assertThatThrownBy(() ->
            OidcPrincipalClaimGuard.verifyTenant(configFor("admin", null, "preferred_username"), "admin"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noOpWhenTenantDisabled() {
        Config c = mock(Config.class);
        when(c.getOptionalValue("quarkus.oidc.admin.tenant-enabled", Boolean.class))
            .thenReturn(Optional.of(false));
        // Legacy key present + correct missing, but tenant off → no enforcement.
        when(c.getOptionalValue("quarkus.oidc.admin.principal-claim", String.class))
            .thenReturn(Optional.of("sub"));
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyTenant(c, "admin")).doesNotThrowAnyException();
    }

    @Test
    void mcpAudiencePassesWhenKumbukaConnector() {
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyMcpAudience(
                audienceConfig(true, "kumbuka-connector")))
            .doesNotThrowAnyException();
    }

    @Test
    void mcpAudienceAbortsWhenAny() {
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyMcpAudience(
                audienceConfig(true, "any")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0032")
            .hasMessageContaining("quarkus.oidc.mcp.token.audience");
    }

    @Test
    void mcpAudienceAbortsWhenMissing() {
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyMcpAudience(
                audienceConfig(true, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0032");
    }

    @Test
    void mcpAudienceAbortsOnMultiValueList() {
        // A comma-joined audience does not string-equal the single expected value.
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyMcpAudience(
                audienceConfig(true, "kumbuka-connector,other-client")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mcpAudienceNoOpWhenMcpTenantDisabled() {
        // Disabled mcp tenant → audience is moot, even at a forbidden value.
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyMcpAudience(
                audienceConfig(false, "any")))
            .doesNotThrowAnyException();
    }

    @Test
    void observerRunsAgainstRealConfigWithoutThrowing() {
        // The test profile disables both guarded tenants, so onStart exercises
        // the observer wiring + the disabled-branch no-op against the real config.
        assertThatCode(() -> guard.onStart(null)).doesNotThrowAnyException();
    }
}
