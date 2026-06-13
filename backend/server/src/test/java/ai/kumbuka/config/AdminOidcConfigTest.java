package ai.kumbuka.config;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for WP2 (the "console logs out after ~5 min" fix) on the
 * kumbuka-server side: the {@code admin} OIDC tenant must keep its silent
 * token-refresh configuration. If these keys are dropped, the access token
 * expires at its ~5 min lifespan and the session is bounced to login instead
 * of refreshing.
 *
 * <p>Note: the deployed SaaS image runs on ops-console's saas-runtime
 * application.properties, which redefines the admin tenant — the equivalent
 * contract test there is the real drift guard (Increment 2). This locks the
 * upstream copy so the two cannot silently diverge.
 */
@QuarkusTest
class AdminOidcConfigTest {

    @Test
    void admin_tenant_keeps_the_silent_refresh_config() {
        Config cfg = ConfigProvider.getConfig();

        assertThat(cfg.getValue("quarkus.oidc.admin.token.refresh-expired", Boolean.class))
            .as("WP2: admin session must silently refresh (refresh-expired=true), else the console "
                + "logs out at the ~5 min access-token lifespan")
            .isTrue();

        assertThat(cfg.getOptionalValue("quarkus.oidc.admin.token.refresh-token-time-skew", String.class))
            .as("WP2: refresh slightly ahead of expiry")
            .isPresent();

        assertThat(cfg.getValue("quarkus.oidc.admin.token-state-manager.strategy", String.class))
            .as("WP2: keep the refresh token in the session state so it can be used")
            .isEqualTo("keep-all-tokens");
    }
}
