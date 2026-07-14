package ai.kumbuka.version;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pin the public version surface: GET /api/version returns the running
 * deployable's version + name + this module's own core version; every
 * response (including this one) carries the X-Kumbuka-Version header.
 */
@QuarkusTest
class VersionResourceTest {

    @Test
    void getVersion_returnsNameVersionAndCore_andStampsHeader() {
        given()
            .when().get("/api/version")
            .then()
                .statusCode(200)
                .header(VersionHeaderFilter.HEADER, notNullValue())
                // In tests Quarkus reports the application version from the
                // pom; in production this is the released tag. Accept any
                // non-blank value rather than pin a literal.
                .body("name", equalTo("kumbuka-server"))
                .body("version", matchesPattern("\\S+"))
                // Standalone build: the deployable IS the core, so the two
                // values are equal (consumers collapse the pair on equality).
                .body("core", matchesPattern("\\S+"))
                // Filtering guard: an unfiltered resource would leak the
                // literal Maven placeholder instead of a version.
                .body("core", not(matchesPattern(".*\\$\\{.*")));
    }

    @Test
    void embeddedCoreVersion_isFilteredAndPresent() {
        String core = VersionResource.readCoreVersion(VersionResource.CORE_VERSION_RESOURCE);
        assertFalse(core.contains("${"), "resource must be Maven-filtered, got: " + core);
        assertFalse(core.isBlank(), "embedded core version must not be blank");
        assertFalse("unknown".equals(core), "embedded resource missing from the build");
    }

    @Test
    void readCoreVersion_missingResource_fallsBackToUnknown() {
        // A miss is a packaging defect surfaced as "unknown", never an
        // exception — version metadata must not take the endpoint down.
        assertEquals("unknown", VersionResource.readCoreVersion("/META-INF/no-such.version"));
    }

    @Test
    void readCoreVersion_blankResource_fallsBackToUnknown() {
        assertEquals("unknown", VersionResource.readCoreVersion("/ai/kumbuka/version/blank.version"));
    }

    @Test
    void standalone_coreEqualsVersion_theConsumerCollapsesThePair() {
        // Standalone, the embedded core version and the application version
        // come from the same pom — pin the equality the consumers (console
        // footer) rely on to show a single value.
        var body = given().when().get("/api/version").then().statusCode(200).extract().jsonPath();
        assertEquals(body.getString("version"), body.getString("core"));
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
