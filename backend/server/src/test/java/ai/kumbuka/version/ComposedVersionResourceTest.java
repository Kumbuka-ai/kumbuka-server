package ai.kumbuka.version;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

/**
 * The composition case of the version surface: a downstream deployable
 * that consumes this module as a dependency reports ITS version as
 * {@code version} (quarkus.application.version names the application that
 * was built), while {@code core} keeps naming this module's own embedded
 * artifact version — the pair a consumer renders as
 * "backend &lt;deployable&gt; (core &lt;core&gt;)".
 */
@QuarkusTest
@TestProfile(ComposedVersionResourceTest.ComposedDeployableProfile.class)
class ComposedVersionResourceTest {

    public static class ComposedDeployableProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.application.version", "9.9.9-test",
                "quarkus.application.name", "kumbuka-composed");
        }
    }

    @Test
    void composedDeployable_reportsItsOwnVersion_andTheEmbeddedCore() {
        given()
            .when().get("/api/version")
            .then()
                .statusCode(200)
                .body("name", equalTo("kumbuka-composed"))
                .body("version", equalTo("9.9.9-test"))
                .body("core", matchesPattern("\\S+"))
                .body("core", not(equalTo("9.9.9-test")));
    }
}
