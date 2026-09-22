package ai.kumbuka.version;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Pin the Vert.x route filter: every response — including ones served
 * outside the JAX-RS pipeline — carries the X-Kumbuka-Version header.
 *
 * <p>The JAX-RS {@link VersionHeaderFilter} only fires on RESTEasy
 * responses; a route served from raw Vert.x skips it entirely. The bearer
 * tool surface was the original such route and left with the memory engine —
 * the management endpoints remain, and this test exercises one of them to
 * prove the Vert.x route filter covers a non-JAX-RS surface.
 *
 * <p>{@code /q/health/live} is the smallrye-health endpoint — served by
 * its own Vert.x handler, NOT through RESTEasy. Perfect smoke target.
 */
@QuarkusTest
class VersionRouteHeaderFilterTest {

    @Test
    void healthEndpoint_carriesVersionHeader_eventhoughNonJaxrs() {
        given()
            .when().get("/q/health/live")
            .then()
                .statusCode(200)
                .header(VersionHeaderFilter.HEADER, notNullValue());
    }
}
