package ai.kumbuka.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.List;

/**
 * Fail-loud startup guard for the D-CORE-12 authorship pin, across every named
 * OIDC tenant that derives an authorship/actor identity.
 *
 * <p>The defect it blocks (finding-dcore12-principal-claim-path-2026-06-15):
 * the key {@code quarkus.oidc.<tenant>.principal-claim} (without the
 * {@code token.} segment) does not exist for a named OIDC tenant in Quarkus
 * 3.33.2. Quarkus ignores it silently and the tenant falls through its default
 * principal chain {@code upn → preferred_username → sub}, stamping the email
 * into {@code memory.owner_subject} (and the governance-audit actor). The
 * original pin shipped mis-pathed and was never effective; only dogfooding
 * caught it because the smoke-gate asserted nothing about identity.
 *
 * <p>Two tenants carry an identity that must be the KC {@code sub}:
 * <ul>
 *   <li>{@code mcp} — the bearer/MCP write path (fixed 16.A).</li>
 *   <li>{@code admin} — the console/BFF write path; console-authored entries
 *       stamp {@code owner_subject} from this principal, and the sub-keyed
 *       erasure (D-CORE-12 strict equality) only matches when it is the sub.</li>
 * </ul>
 *
 * <p>This guard makes a future regression structural rather than silent: if a
 * tenant is enabled, startup aborts unless its pin is effective. It ships in
 * kumbuka-server and therefore protects both the OSS standalone and the SaaS
 * runtime (which augments these beans).
 *
 * <p>It also carries a second, mcp-specific boot invariant (ADR-0032 § 3):
 * when the {@code mcp} tenant is enabled, the {@code /mcp} bearer path must
 * validate the token audience against exactly {@code kumbuka-connector} — the
 * single generic connector client that the endpoint collapse left. A wider or
 * absent audience ({@code any}, empty, a multi-value list) would let a token
 * minted for a different client be replayed against {@code /mcp}. Asserting it
 * at boot turns the ee-server overlay's audience value into a fail-loud contract
 * rather than a config knob that can silently drift back to {@code any}.
 *
 * <p>Scope note: this catches the known mis-path, "claim not effective", and a
 * non-narrowed {@code /mcp} audience; it does not enumerate every conceivable
 * unrecognized {@code oidc.<tenant>.*} key — there is no registry of valid OIDC
 * keys to diff against.
 */
@ApplicationScoped
public class OidcPrincipalClaimGuard {

    /** Named OIDC tenants whose principal must resolve to the KC {@code sub}. */
    static final List<String> GUARDED_TENANTS = List.of("mcp", "admin");

    private static final String PREFIX = "quarkus.oidc.";
    static final String EXPECTED = "sub";

    /** The single generic connector client the {@code /mcp} audience must equal (ADR-0032 § 3). */
    static final String MCP_TENANT = "mcp";
    static final String MCP_AUDIENCE_KEY = PREFIX + MCP_TENANT + ".token.audience";
    static final String EXPECTED_AUDIENCE = "kumbuka-connector";

    @Inject
    Config config;

    void onStart(@Observes StartupEvent ev) {
        verify(config);
    }

    static void verify(Config config) {
        for (String tenant : GUARDED_TENANTS) {
            verifyTenant(config, tenant);
        }
        verifyMcpAudience(config);
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
                + "'. See D-CORE-12 / finding-dcore12-principal-claim-path.");
        }

        // Positive assertion: the pin must actually be effective.
        String actual = config.getOptionalValue(correctKey, String.class).orElse(null);
        if (!EXPECTED.equals(actual)) {
            throw new IllegalStateException(
                "D-CORE-12 invariant violated: '" + correctKey + "' must be '" + EXPECTED + "' "
                + "(authorship = Keycloak sub per ADR-0008), but was '" + actual + "'. Without it the '"
                + tenant + "' tenant stamps preferred_username (email) into owner_subject.");
        }
    }

    /**
     * ADR-0032 § 3 (audience collapse): when the {@code mcp} tenant is enabled,
     * the {@code /mcp} bearer path must validate the token audience against
     * exactly {@code kumbuka-connector} — the single generic connector client.
     * A wider value ({@code any}), an empty/absent audience, or a multi-value
     * list (which does not string-equal the expected single value) would let a
     * token minted for a different client be replayed against {@code /mcp}
     * (token-confusion). Kept out of {@link #GUARDED_TENANTS} because the
     * {@code admin} tenant carries no such requirement.
     */
    static void verifyMcpAudience(Config config) {
        boolean enabled = config
            .getOptionalValue(PREFIX + MCP_TENANT + ".tenant-enabled", Boolean.class)
            .orElse(true);
        if (!enabled) {
            // mcp tenant off (e.g. data-layer tests) — the audience is moot.
            return;
        }

        String actual = config.getOptionalValue(MCP_AUDIENCE_KEY, String.class).orElse(null);
        if (!EXPECTED_AUDIENCE.equals(actual)) {
            throw new IllegalStateException(
                "ADR-0032 § 3 invariant violated: '" + MCP_AUDIENCE_KEY + "' must be '"
                + EXPECTED_AUDIENCE + "' (the single generic connector client), but was '" + actual
                + "'. A wider or absent audience (e.g. 'any') lets a token minted for another client "
                + "be replayed against /mcp — token-confusion.");
        }
    }
}
