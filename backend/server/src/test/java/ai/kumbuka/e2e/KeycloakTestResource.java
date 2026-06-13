package ai.kumbuka.e2e;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Fixed host port. The OIDC {@code auth-server-url} must be known at BUILD
     * time so {@link OidcEnabledProfile} (a {@code @TestProfile}, whose config
     * overrides have the precedence the OIDC tenant needs) can point the tenant
     * at this container — a {@code QuarkusTestResource}'s runtime config map does
     * NOT reliably win for {@code auth-server-url}. A random mapped port can't be
     * known at build time, hence the fixed binding.
     */
    public static final int HOST_PORT = 38080;
    public static final String BASE_URL = "http://localhost:" + HOST_PORT;
    public static final String ISSUER = BASE_URL + "/realms/" + REALM;

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
        // Bind container 8080 to a fixed host port so ISSUER is deterministic.
        keycloak.setPortBindings(List.of(HOST_PORT + ":8080"));
        keycloak.start();

        // Point the mcp OIDC tenant at the test Keycloak via SYSTEM PROPERTIES.
        // The OIDC auth-server-url ignored every lower-ordinal override we tried
        // (QuarkusTestProfile.getConfigOverrides, %test keys, application-test
        // .properties) — system properties (ordinal 400) sit above the
        // application.properties expression and are read at boot, which happens
        // after this resource starts. Cleared in stop().
        System.setProperty("quarkus.oidc.mcp.auth-server-url", ISSUER);
        System.setProperty("KUMBUKA_OIDC_ISSUER", ISSUER);

        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.keycloak.devservices.enabled", "false");
        cfg.put("test.keycloak.issuer", ISSUER);
        cfg.put("test.keycloak.base-url", BASE_URL);
        return cfg;
    }

    @Override
    public void stop() {
        System.clearProperty("quarkus.oidc.mcp.auth-server-url");
        System.clearProperty("KUMBUKA_OIDC_ISSUER");
        if (keycloak != null) {
            keycloak.stop();
            keycloak = null;
        }
    }
}
