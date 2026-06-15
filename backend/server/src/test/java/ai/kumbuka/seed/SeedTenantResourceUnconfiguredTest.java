package ai.kumbuka.seed;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies that {@code /api/internal/seed-tenant} <strong>fails loud (503)</strong>
 * on a host where the operator forgot to configure the shared-secret token.
 * Mirrors {@code EraseSubjectResourceUnconfiguredTest} — same fail-loud
 * contract, same {@link TestProfile} trick to flip the token to empty.
 */
@QuarkusTest
@TestProfile(SeedTenantResourceUnconfiguredTest.UnsetTokenProfile.class)
class SeedTenantResourceUnconfiguredTest {

    public static class UnsetTokenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.internal.seed.token", "");
        }
    }

    @InjectMock TenantSeedService seedService;

    private static final UUID SINGLETON_TENANT =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void serviceUnavailable_whenTokenUnset() {
        given()
            // Even with a bearer header, the host's lack of configuration
            // means the endpoint refuses to do anything.
            .header("Authorization", "Bearer anything")
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/seed-tenant")
            .then()
                .statusCode(503)
                .body("error", equalTo("seed_endpoint_not_configured"))
                .body("message", containsString("seed.token"));

        verify(seedService, never()).seedCurrentTenant();
    }
}
