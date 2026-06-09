package ai.kumbuka.erasure;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies that the endpoint <strong>fails loud (503)</strong> on a host
 * where the operator forgot to configure the shared-secret token. This is
 * the "unconfigured deploy doesn't silently accept any caller" contract
 * from {@link EraseSubjectResource}'s javadoc.
 *
 * Uses a {@link TestProfile} that overrides the token to empty; the
 * default test profile keeps it set so the other tests can exercise the
 * authenticated paths.
 */
@QuarkusTest
@TestProfile(EraseSubjectResourceUnconfiguredTest.UnsetTokenProfile.class)
class EraseSubjectResourceUnconfiguredTest {

    public static class UnsetTokenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.internal.erasure.token", "");
        }
    }

    @InjectMock MemberErasureService erasure;

    private static final UUID SINGLETON_TENANT =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void serviceUnavailable_whenTokenUnset() {
        given()
            // Even with a bearer header, the host's lack of configuration
            // means the endpoint refuses to do anything.
            .header("Authorization", "Bearer anything")
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\",\"subject\":\"alice\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(503)
                .body("error", equalTo("erase_endpoint_not_configured"))
                .body("message", containsString("erasure.token"));

        verify(erasure, never()).eraseSubject(any());
    }
}
