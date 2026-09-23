package ai.kumbuka.erasure;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-contract tests for {@code POST /api/internal/purge-tenant}.
 * The service is mocked; real DB behaviour is exercised in
 * {@link TenantDataPurgeServiceTest}.
 *
 * Invariants:
 *   • bearer absent / wrong → 401, service NOT called
 *   • body missing tenantId → 400, service NOT called
 *   • body's tenantId != resolver's tenant → 400 tenant_mismatch
 *   • a call that passes every check → 503 erasure_path_incomplete,
 *     service NOT called
 */
@QuarkusTest
class PurgeTenantResourceTest {

    @InjectMock TenantDataPurgeService purger;

    private static final UUID SINGLETON_TENANT =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "test-erase-token";

    @Test
    void unauthorized_whenBearerMissing() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/purge-tenant")
            .then()
                .statusCode(401)
                .body("error", equalTo("unauthorized"));
        verify(purger, never()).purgeTenant(anyString());
    }

    @Test
    void unauthorized_whenBearerWrong() {
        given()
            .header("Authorization", "Bearer nope")
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/purge-tenant")
            .then()
                .statusCode(401);
        verify(purger, never()).purgeTenant(anyString());
    }

    @Test
    void badRequest_whenTenantIdMissing() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post("/api/internal/purge-tenant")
            .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"));
        verify(purger, never()).purgeTenant(anyString());
    }

    @Test
    void badRequest_whenTenantDoesNotMatchResolver() {
        UUID other = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + other + "\"}")
            .when().post("/api/internal/purge-tenant")
            .then()
                .statusCode(400)
                .body("error", equalTo("tenant_mismatch"));
        verify(purger, never()).purgeTenant(anyString());
    }

    /**
     * A call that passes every check is refused, and the purge service is
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
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/purge-tenant")
            .then()
                .statusCode(503)
                .body("error", equalTo("erasure_path_incomplete"))
                .body("message", equalTo(
                    "tenant purge is refused: the erasure path across the service "
                  + "boundary is not built yet, and this service alone cannot purge "
                  + "a tenant's data"))
                // No count of any kind: a number here reads as a purge that
                // happened, and none did.
                .body("$", not(hasKey("memoryDeleted")))
                .body("$", not(hasKey("userAccountsDeleted")))
                .body("$", not(hasKey("teamSettingsDeleted")))
                .body("$", not(hasKey("scopesDeleted")))
                .body("$", not(hasKey("teamDeleted")));

        verify(purger, never()).purgeTenant(anyString());
    }
}
