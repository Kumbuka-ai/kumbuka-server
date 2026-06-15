package ai.kumbuka.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Fail-loud startup guard for the D-CORE-12 authorship pin.
 *
 * <p>The defect it blocks (finding-dcore12-principal-claim-path-2026-06-15):
 * the key {@code quarkus.oidc.mcp.principal-claim} (without the {@code token.}
 * segment) does not exist for a named OIDC tenant in Quarkus 3.33.2. Quarkus
 * ignores it silently and the {@code mcp} service tenant falls through its
 * default principal chain {@code upn → preferred_username → sub}, stamping the
 * email into {@code memory.owner_subject} and breaking the erase-by-subject
 * contract. The original pin shipped mis-pathed and was never effective; only
 * dogfooding caught it because the smoke-gate asserted nothing about identity.
 *
 * <p>This guard makes a future regression structural rather than silent: if the
 * mcp tenant is enabled, startup aborts unless the pin is effective. It ships in
 * kumbuka-server and therefore protects both the OSS standalone and the SaaS
 * runtime (which augments these beans).
 *
 * <p>Scope note: this catches the known mis-path and "claim not effective"; it
 * does not enumerate every conceivable unrecognized {@code oidc.mcp.*} key —
 * there is no registry of valid OIDC keys to diff against.
 */
@ApplicationScoped
public class OidcMcpPrincipalClaimGuard {

    static final String TENANT_ENABLED_KEY = "quarkus.oidc.mcp.tenant-enabled";
    static final String LEGACY_KEY = "quarkus.oidc.mcp.principal-claim";
    static final String CORRECT_KEY = "quarkus.oidc.mcp.token.principal-claim";
    static final String EXPECTED = "sub";

    @Inject
    Config config;

    void onStart(@Observes StartupEvent ev) {
        verify(config);
    }

    static void verify(Config config) {
        boolean enabled = config.getOptionalValue(TENANT_ENABLED_KEY, Boolean.class).orElse(true);
        if (!enabled) {
            // mcp tenant off (e.g. data-layer tests) — principal-claim is moot.
            return;
        }

        // Tripwire: the exact mis-path that hid the original defect. If anyone
        // reintroduces the un-segmented key, abort with a pointer to the fix.
        if (config.getOptionalValue(LEGACY_KEY, String.class).isPresent()) {
            throw new IllegalStateException(
                "Mis-pathed OIDC config: '" + LEGACY_KEY + "' is set but Quarkus ignores it for a "
                + "named tenant (principal-claim lives under 'token.'). Use '" + CORRECT_KEY + "=" + EXPECTED
                + "'. See D-CORE-12 / finding-dcore12-principal-claim-path.");
        }

        // Positive assertion: the pin must actually be effective.
        String actual = config.getOptionalValue(CORRECT_KEY, String.class).orElse(null);
        if (!EXPECTED.equals(actual)) {
            throw new IllegalStateException(
                "D-CORE-12 invariant violated: '" + CORRECT_KEY + "' must be '" + EXPECTED + "' "
                + "(authorship = Keycloak sub per ADR-0008), but was '" + actual + "'. Without it the mcp "
                + "service tenant stamps preferred_username (email) into owner_subject.");
        }
    }
}
