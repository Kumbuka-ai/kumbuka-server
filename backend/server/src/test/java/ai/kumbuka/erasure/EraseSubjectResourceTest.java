package ai.kumbuka.erasure;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-contract tests for {@code POST /api/internal/erase-subject}.
 * The service layer is mocked — those branches are exercised in
 * {@link MemberErasureServiceTest}. This class covers the
 * resource-level invariants:
 *
 *   • bearer token absent / wrong → 401, service NOT called
 *   • body missing tenantId / subject → 400, service NOT called
 *   • body's tenantId does not equal the resolver's tenant → 400,
 *     service NOT called (misroute guard)
 *   • a call that passes every check → 503 erasure_path_incomplete,
 *     service NOT called
 *
 * The 503 case (token unset on the host) lives in
 * {@link EraseSubjectResourceUnconfiguredTest}, which runs under a
 * @TestProfile that overrides the token to empty.
 */
@QuarkusTest
class EraseSubjectResourceTest {

    @InjectMock MemberErasureService erasure;

    /** Matches kumbuka.tenant-id in test/resources/application.properties. */
    private static final UUID SINGLETON_TENANT =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Matches kumbuka.internal.erasure.token in test/resources/application.properties. */
    private static final String TOKEN = "test-erase-token";

    @Test
    void unauthorized_whenBearerHeaderMissing() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\",\"subject\":\"alice\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(401)
                .body("error", equalTo("unauthorized"));

        verify(erasure, never()).eraseSubject(any());
    }

    @Test
    void unauthorized_whenBearerHeaderWrong() {
        given()
            .header("Authorization", "Bearer wrong-token")
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\",\"subject\":\"alice\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(401);

        verify(erasure, never()).eraseSubject(any());
    }

    @Test
    void badRequest_whenTenantIdMissing() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"subject\":\"alice\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"));

        verify(erasure, never()).eraseSubject(any());
    }

    @Test
    void badRequest_whenSubjectBlank() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\",\"subject\":\"   \"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"));

        verify(erasure, never()).eraseSubject(any());
    }

    @Test
    void badRequest_whenTenantDoesNotMatchResolver() {
        UUID otherTenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + otherTenant + "\",\"subject\":\"alice\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(400)
                .body("error", equalTo("tenant_mismatch"))
                .body("message", containsString("tenant"));

        verify(erasure, never()).eraseSubject(any());
    }

    /**
     * A call that passes every check is refused, and the erasure service is
     * never reached. This is the resource-level half of the statement; the
     * half that counts rows lives in {@link ErasureEndpointsRefuseIT}.
     * Both are needed: a mock can
     * prove the service was not called, and only a real database can prove
     * that nothing else wrote either.
     */
    @Test
    void validCall_isRefused_andNeverReachesTheService() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\",\"subject\":\"alice-kc-sub\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(503)
                .body("error", equalTo("erasure_path_incomplete"))
                .body("message", equalTo(
                    "member erasure is refused: the erasure path across the service "
                  + "boundary is not built yet, and this service alone cannot erase "
                  + "a member's data"))
                // No count of any kind: a number here reads as an erasure that
                // happened, and none did.
                .body("$", not(hasKey("scopesTombstoned")))
                .body("$", not(hasKey("privatePurged")))
                .body("$", not(hasKey("sharedTombstoned")));

        verify(erasure, never()).eraseSubject(any());
    }
}
