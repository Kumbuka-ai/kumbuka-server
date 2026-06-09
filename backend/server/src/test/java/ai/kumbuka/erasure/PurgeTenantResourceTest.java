package ai.kumbuka.erasure;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
 *   • happy path → 200, counts pass-through, service called with the
 *     tenant id from the body
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

    @Test
    void happyPath_returnsCountsAndCallsService() {
        when(purger.purgeTenant(SINGLETON_TENANT.toString())).thenReturn(
            new TenantDataPurgeService.PurgeResult(7, 2, 1, 3, 1));

        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/purge-tenant")
            .then()
                .statusCode(200)
                .body("memoryDeleted", equalTo(7))
                .body("userAccountsDeleted", equalTo(2))
                .body("teamSettingsDeleted", equalTo(1))
                .body("scopesDeleted", equalTo(3))
                .body("teamDeleted", equalTo(1));

        verify(purger).purgeTenant(SINGLETON_TENANT.toString());
    }
}
