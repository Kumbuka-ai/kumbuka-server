package ai.kumbuka.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.List;

/**
 * Fail-loud startup guard for the authorship pin, across every named
 * OIDC tenant that derives an authorship/actor identity.
 *
 * <p>The defect it blocks:
 * the key {@code quarkus.oidc.<tenant>.principal-claim} (without the
 * {@code token.} segment) does not exist for a named OIDC tenant in Quarkus
 * 3.33.2. Quarkus ignores it silently and the tenant falls through its default
 * principal chain {@code upn → preferred_username → sub}, stamping the email
 * into the governance-audit actor. The
 * original pin shipped mis-pathed and was never effective; only dogfooding
 * caught it because the smoke-gate asserted nothing about identity.
 *
 * <p>One tenant carries an identity that must be the KC {@code sub}:
 * {@code admin}, the console/BFF write path — the governance audit stamps its
 * actor from this principal, and the sub-keyed erasure (strict equality) only
 * matches when it is the sub. The {@code mcp} bearer tenant was the second one
 * until the memory engine left the core; it is gone with the surface it served.
 *
 * <p>This guard makes a future regression structural rather than silent: if a
 * tenant is enabled, startup aborts unless its pin is effective. It ships in
 * kumbuka-server and therefore protects both the OSS standalone and the SaaS
 * runtime (which augments these beans).
 *
 * <p>Scope note: this catches the known mis-path and "claim not effective"; it
 * does not enumerate every conceivable unrecognized {@code oidc.<tenant>.*}
 * key — there is no registry of valid OIDC keys to diff against.
 */
@ApplicationScoped
public class OidcPrincipalClaimGuard {

    /** Named OIDC tenants whose principal must resolve to the KC {@code sub}. */
    static final List<String> GUARDED_TENANTS = List.of("admin");

    private static final String PREFIX = "quarkus.oidc.";
    static final String EXPECTED = "sub";

    @Inject
    Config config;

    void onStart(@Observes StartupEvent ev) {
        verify(config);
    }

    static void verify(Config config) {
        for (String tenant : GUARDED_TENANTS) {
            verifyTenant(config, tenant);
        }
    }

    static void verifyTenant(Config config, String tenant) {
        final String enabledKey = PREFIX + tenant + ".tenant-enabled";
        final String legacyKey = PREFIX + tenant + ".principal-claim";
        final String correctKey = PREFIX + tenant + ".token.principal-claim";

        boolean enabled = config.getOptionalValue(enabledKey, Boolean.class).orElse(true);
        if (!enabled) {
            // tenant off (e.g. data-layer tests) — principal-claim is moot.
            return;
        }

        // Tripwire: the exact mis-path that hid the original defect. If anyone
        // reintroduces the un-segmented key, abort with a pointer to the fix.
        if (config.getOptionalValue(legacyKey, String.class).isPresent()) {
            throw new IllegalStateException(
                "Mis-pathed OIDC config: '" + legacyKey + "' is set but Quarkus ignores it for a "
                + "named tenant (principal-claim lives under 'token.'). Use '" + correctKey + "=" + EXPECTED
                + "'.");
        }

        // Positive assertion: the pin must actually be effective.
        String actual = config.getOptionalValue(correctKey, String.class).orElse(null);
        if (!EXPECTED.equals(actual)) {
            throw new IllegalStateException(
                "principal-claim invariant violated: '" + correctKey + "' must be '" + EXPECTED + "' "
                + "(authorship = Keycloak sub per ADR-0008), but was '" + actual + "'. Without it the '"
                + tenant + "' tenant stamps preferred_username (email) as the acting subject.");
        }
    }
}
