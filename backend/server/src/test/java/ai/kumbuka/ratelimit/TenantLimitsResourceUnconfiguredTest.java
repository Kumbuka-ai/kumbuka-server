package ai.kumbuka.ratelimit;

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

/**
 * The internal limits endpoint fails loud (503) on a host where the
 * operator has not configured the shared-secret token — same fail-loud
 * contract and {@link TestProfile} trick as the seed/erase endpoints.
 */
@QuarkusTest
@TestProfile(TenantLimitsResourceUnconfiguredTest.UnsetTokenProfile.class)
class TenantLimitsResourceUnconfiguredTest {

    public static class UnsetTokenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.internal.limits.token", "");
        }
    }

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void serviceUnavailableWhenTokenUnset() {
        given()
            .header("Authorization", "Bearer anything")
            .when().get("/api/internal/tenants/" + TENANT + "/limits")
            .then()
                .statusCode(503)
                .body("error", equalTo("limits_endpoint_not_configured"))
                .body("message", containsString("kumbuka.internal.limits.token"));

        given()
            .header("Authorization", "Bearer anything")
            .contentType(ContentType.JSON)
            .body("{\"write\":null,\"tenantWrite\":null}")
            .when().patch("/api/internal/tenants/" + TENANT + "/limits")
            .then()
                .statusCode(503);
    }
}
