package ai.kumbuka.version;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Pin the public version surface: GET /api/version returns the running
 * version + name; every response (including this one) carries the
 * X-Kumbuka-Version header.
 */
@QuarkusTest
class VersionResourceTest {

    @Test
    void getVersion_returnsNameAndVersion_andStampsHeader() {
        given()
            .when().get("/api/version")
            .then()
                .statusCode(200)
                .header(VersionHeaderFilter.HEADER, notNullValue())
                // In tests Quarkus reports the application version from the
                // pom; in production this is the released tag. Accept any
                // non-blank value rather than pin a literal.
                .body("name", equalTo("kumbuka-server"))
                .body("version", matchesPattern("\\S+"));
    }

    @Test
    void headerStampedOnAnotherJaxrsEndpoint_notJustVersion() {
        // Probe a different JAX-RS endpoint to prove the @Provider filter
        // covers every response, not just /api/version. The well-known
        // OAuth protected-resource metadata is @PermitAll, always 200.
        // ({@code /q/*} is the Quarkus management interface — outside the
        // JAX-RS pipeline, so the filter doesn't apply there. Operators
        // curl /api/* to read the header, which is what matters.)
        given()
            .when().get("/.well-known/oauth-protected-resource")
            .then()
                .statusCode(200)
                .header(VersionHeaderFilter.HEADER, notNullValue());
    }
}
