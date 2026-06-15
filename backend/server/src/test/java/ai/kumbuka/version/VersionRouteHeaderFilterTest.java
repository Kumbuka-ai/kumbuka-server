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
 * responses; routes like {@code /mcp} (served by quarkus-mcp-server-http
 * via raw Vert.x) skip it entirely. This test exercises a non-JAX-RS
 * surface to prove the Vert.x route filter covers it.
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
