package ai.kumbuka.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile that re-enables the OIDC tenants for the OAuth end-to-end IT.
 *
 * <p>{@code quarkus.oidc.*.tenant-enabled} is a BUILD-TIME property — the
 * default test profile bakes it to {@code false} (so data-layer tests need no
 * Keycloak), and a {@code @QuarkusTestResource} cannot flip it back at runtime.
 * A {@link QuarkusTestProfile} override IS applied at augmentation, so Quarkus
 * re-augments the app for this profile with OIDC ON. {@link KeycloakTestResource}
 * then supplies the matching {@code auth-server-url} (the live container) at
 * runtime. Together they let {@link E2EOAuthIntegrationIT} drive the real bearer
 * flow against the {@code /mcp} endpoint.
 */
public class OidcEnabledProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // The OAuth IT exercises the bearer-protected /mcp tenant + the public
        // /.well-known endpoint, so only the mcp tenant needs enabling. All URLs
        // point at the fixed-port test Keycloak (KeycloakTestResource.ISSUER) —
        // set here (build time) because the OIDC tenant won't pick them up from
        // the resource's runtime config map, and the well-known endpoint reads
        // kumbuka.auth-base-url, which otherwise defaults to the prod host.
        return Map.of(
            "quarkus.oidc.mcp.tenant-enabled", "true",
            "quarkus.oidc.mcp.auth-server-url", KeycloakTestResource.ISSUER,
            "kumbuka.auth-base-url", KeycloakTestResource.BASE_URL,
            "test.keycloak.issuer", KeycloakTestResource.ISSUER,
            "test.keycloak.base-url", KeycloakTestResource.BASE_URL);
    }
}
