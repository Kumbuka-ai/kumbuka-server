package ai.kumbuka.erasure;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
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
 *   • happy path → 200 + counts pass-through; service called with the
 *     subject from the body
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

    @Test
    void happyPath_returnsCountsAndCallsServiceWithSubject() {
        when(erasure.eraseSubject("alice-kc-sub"))
            .thenReturn(new MemberErasureService.EraseResult(7, 3, 1));

        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\",\"subject\":\"alice-kc-sub\"}")
            .when().post("/api/internal/erase-subject")
            .then()
                .statusCode(200)
                .body("privatePurged", equalTo(7))
                .body("sharedTombstoned", equalTo(3))
                .body("scopesTombstoned", equalTo(1));

        verify(erasure).eraseSubject("alice-kc-sub");
    }
}
