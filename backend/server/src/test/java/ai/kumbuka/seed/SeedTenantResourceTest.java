package ai.kumbuka.seed;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-contract tests for {@code POST /api/internal/seed-tenant} (D-CORE-11).
 * The service layer is mocked — seeding itself is exercised by
 * {@code ai.kumbuka.repo.ProtectedSeedTest}. This class covers the
 * resource-level invariants:
 *
 * <ul>
 *   <li>bearer token absent / wrong → 401, service NOT called</li>
 *   <li>body missing tenantId → 400, service NOT called</li>
 *   <li>body's tenantId does not equal the resolver's tenant → 400,
 *       service NOT called (misroute guard)</li>
 *   <li>happy path → 200 + fixture-size + key list returned;
 *       service called exactly once</li>
 * </ul>
 *
 * The 503 case (token unset on the host) lives in
 * {@link SeedTenantResourceUnconfiguredTest}, which runs under a
 * {@code @TestProfile} that overrides the token to empty.
 */
@QuarkusTest
class SeedTenantResourceTest {

    @InjectMock TenantSeedService seedService;

    /** Matches kumbuka.tenant-id in test/resources/application.properties. */
    private static final UUID SINGLETON_TENANT =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Matches kumbuka.internal.seed.token in test/resources/application.properties. */
    private static final String TOKEN = "test-seed-token";

    @Test
    void unauthorized_whenBearerHeaderMissing() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/seed-tenant")
            .then()
                .statusCode(401)
                .body("error", equalTo("unauthorized"));

        verify(seedService, never()).seedCurrentTenant();
    }

    @Test
    void unauthorized_whenBearerHeaderWrong() {
        given()
            .header("Authorization", "Bearer wrong-token")
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/seed-tenant")
            .then()
                .statusCode(401);

        verify(seedService, never()).seedCurrentTenant();
    }

    @Test
    void badRequest_whenTenantIdMissing() {
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post("/api/internal/seed-tenant")
            .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"));

        verify(seedService, never()).seedCurrentTenant();
    }

    @Test
    void badRequest_whenTenantIdDoesNotMatchResolverTenant() {
        UUID misroute = UUID.fromString("11111111-2222-3333-4444-555555555555");
        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + misroute + "\"}")
            .when().post("/api/internal/seed-tenant")
            .then()
                .statusCode(400)
                .body("error", equalTo("tenant_mismatch"))
                .body("requested", equalTo(misroute.toString()))
                .body("resolved", equalTo(SINGLETON_TENANT.toString()));

        verify(seedService, never()).seedCurrentTenant();
    }

    @Test
    void happyPath_returnsFixtureSummary_andCallsServiceOnce() {
        when(seedService.fixtureSize()).thenReturn(3);
        when(seedService.fixtureKeys()).thenReturn(List.of(
            "convention.how-to-kumbuka.types",
            "convention.how-to-kumbuka.writing",
            "convention.how-to-kumbuka.reading"));

        given()
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(ContentType.JSON)
            .body("{\"tenantId\":\"" + SINGLETON_TENANT + "\"}")
            .when().post("/api/internal/seed-tenant")
            .then()
                .statusCode(200)
                .body("tenantId", equalTo(SINGLETON_TENANT.toString()))
                .body("seedsApplied", is(3))
                .body("keys.size()", is(3));

        verify(seedService, times(1)).seedCurrentTenant();
    }
}
