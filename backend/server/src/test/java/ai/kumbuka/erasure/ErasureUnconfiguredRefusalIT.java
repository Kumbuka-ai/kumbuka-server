package ai.kumbuka.erasure;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * On a host where the shared secret was never configured, both endpoints
 * still answer the refusal they always answered — and not the new one.
 *
 * <h3>Why this needs its own probe</h3>
 *
 * <p>Both refusals are 503. A probe that only read the status could not tell
 * them apart, and an ordering mistake — the erasure-path refusal placed
 * ahead of the configuration check — would look green while an unconfigured
 * host silently stopped reporting that it is unconfigured. The error
 * identifier is what separates them, so that is what this probe reads.
 */
@QuarkusTest
@TestProfile(ErasureUnconfiguredRefusalIT.UnsetToken.class)
@Tag("integration")
class ErasureUnconfiguredRefusalIT {

    /** The V1 singleton — no fixture is planted, because no call gets far enough to touch one. */
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static class UnsetToken implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.internal.erasure.token", "");
        }
    }

    @Test
    void eraseSubjectStillReportsThatItIsUnconfigured() {
        assertUnconfigured(
            post("/api/internal/erase-subject",
                 "{\"tenantId\":\"" + TENANT + "\",\"subject\":\"somebody\"}"),
            "erase_endpoint_not_configured",
            "erase-subject");
    }

    @Test
    void purgeTenantStillReportsThatItIsUnconfigured() {
        assertUnconfigured(
            post("/api/internal/purge-tenant", "{\"tenantId\":\"" + TENANT + "\"}"),
            "purge_endpoint_not_configured",
            "purge-tenant");
    }

    private static void assertUnconfigured(Response answer, String expectedError, String endpoint) {
        assertThat(answer.statusCode())
            .as("%s answers 503 on an unconfigured host, as it always did. Body: %s",
                endpoint, answer.asString())
            .isEqualTo(503);

        assertThat(answer.jsonPath().getString("error"))
            .as("%s must still say it is UNCONFIGURED. Reading '%s' here means the "
                + "erasure-path refusal was placed ahead of the configuration check, "
                + "and an operator with an unconfigured host would stop being told so",
                endpoint, ErasurePathIncomplete.ERROR)
            .isEqualTo(expectedError);
    }

    private static Response post(String path, String body) {
        return given()
            // A bearer header that would be accepted on a configured host: the
            // point is that the missing configuration answers before anything else.
            .header("Authorization", "Bearer anything")
            .contentType(ContentType.JSON)
            .body(body)
            .when().post(path)
            .andReturn();
    }
}
