package ai.kumbuka.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring of the write-rate limiter on the console write API,
 * against the real database: the name-bound request filter throttles
 * entry creation past the configured burst with 429 + Retry-After, while
 * reads stay unlimited. Runs under a tiny default band (burst 3, refill
 * 1/60s) so the series is exact and free of wall-clock refill.
 */
@QuarkusTest
@Tag("integration")
@TestProfile(ConsoleWriteRateLimitIT.TinyBandProfile.class)
class ConsoleWriteRateLimitIT {

    public static class TinyBandProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "kumbuka.rate-limit.default-burst-capacity", "3",
                "kumbuka.rate-limit.default-refill-tokens", "1",
                "kumbuka.rate-limit.default-refill-period-seconds", "60");
        }
    }

    @Test
    @TestSecurity(user = "console-writer", roles = {"member"})
    void writesPassWithinBurstThenThrottleWith429() {
        for (int i = 0; i < 3; i++) {
            Response ok = given()
                .contentType(ContentType.JSON)
                .body("{\"content\":\"within band " + i + "\",\"type\":\"status\"}")
                .when().post("/api/scopes/global/entries");
            assertThat(ok.statusCode())
                .as("write %d within the burst capacity is not throttled", i + 1)
                .isEqualTo(201);
        }

        Response throttled = given()
            .contentType(ContentType.JSON)
            .body("{\"content\":\"past band\",\"type\":\"status\"}")
            .when().post("/api/scopes/global/entries");

        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).isNotBlank();
        assertThat(throttled.jsonPath().getString("error")).isEqualTo("rate_limited");
        assertThat(throttled.jsonPath().getLong("retryAfterSeconds")).isPositive();
    }

    @Test
    @TestSecurity(user = "console-reader", roles = {"member"})
    void readsAreNeverThrottled() {
        for (int i = 0; i < 10; i++) {
            given()
                .when().get("/api/scopes/global/entries")
                .then().statusCode(200);
        }
    }
}
