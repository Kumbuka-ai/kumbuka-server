package ai.kumbuka.e2e;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots Keycloak 26 in a Testcontainers GenericContainer for the E2E
 * integration test. Mounts a test-only realm file (kumbuka-realm-it.json)
 * that mirrors prod but enables {@code directAccessGrantsEnabled} on
 * the kumbuka-connector client so tests can obtain real tokens via
 * password grant without simulating the authorisation-code flow.
 *
 * Returns Quarkus config overrides that point the OIDC tenants at this
 * container's randomly-mapped port.
 */
public class KeycloakTestResource implements QuarkusTestResourceLifecycleManager {

    public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0";
    public static final String REALM = "kumbuka";

    private static GenericContainer<?> keycloak;

    @Override
    public Map<String, String> start() {
        keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("kumbuka-realm-it.json"),
                "/opt/keycloak/data/import/kumbuka-realm-it.json"
            )
            .waitingFor(
                Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .withStartupTimeout(Duration.ofMinutes(3))
            );
        keycloak.start();

        String issuer = "http://" + keycloak.getHost()
            + ":" + keycloak.getMappedPort(8080)
            + "/realms/" + REALM;
        String adminUrl = "http://" + keycloak.getHost()
            + ":" + keycloak.getMappedPort(8080);

        Map<String, String> cfg = new HashMap<>();
        // Activate the OIDC tenants that the regular test profile disables.
        cfg.put("quarkus.oidc.mcp.tenant-enabled", "true");
        cfg.put("quarkus.oidc.admin.tenant-enabled", "true");
        // Set BOTH the typed property and the env-var-expanded source so any
        // ${KUMBUKA_OIDC_ISSUER:default} in application.properties resolves
        // to the test KC instead of the prod default.
        cfg.put("quarkus.oidc.mcp.auth-server-url", issuer);
        cfg.put("quarkus.oidc.admin.auth-server-url", issuer);
        cfg.put("KUMBUKA_OIDC_ISSUER", issuer);
        cfg.put("KUMBUKA_KEYCLOAK_URL", adminUrl);
        // The admin tenant in BFF mode requires a secret to start, even though
        // these tests don't exercise the code/cookie flow.
        cfg.put("quarkus.oidc.admin.credentials.secret", "change-me-kumbuka-admin-secret");
        // Keycloak Admin client.
        cfg.put("quarkus.keycloak.admin-client.server-url", adminUrl);
        cfg.put("quarkus.keycloak.devservices.enabled", "false");
        // Surface the test URL on the SUT itself so test code can read it.
        cfg.put("test.keycloak.issuer", issuer);
        cfg.put("test.keycloak.base-url", adminUrl);
        return cfg;
    }

    @Override
    public void stop() {
        if (keycloak != null) {
            keycloak.stop();
            keycloak = null;
        }
    }
}
