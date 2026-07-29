package ai.kumbuka.overlay;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * End-to-end gate for the listing counters and the console read-only flag. A
 * fresh tenant carries no bundled guidance rows, so the overlay surfaces on the
 * global scope. Because the overview total, the per-scope entry count and the
 * entries listing are all built from {@code SharedMemoryRepository.listShared},
 * the overlay is reflected consistently across all three surfaces.
 *
 * <p>Read-only endpoints over committed state — no {@code @TestTransaction}.
 */
@QuarkusTest
class GuidanceCountersAndReadOnlyTest {

    private static final String KEY_TYPES = "system.how-to-kumbuka.types";
    private static final String KEY_WRITING = "system.how-to-kumbuka.writing";
    private static final String KEY_READING = "system.how-to-kumbuka.reading";

    @Test
    @TestSecurity(user = "member-reader", roles = {"member"})
    void entriesListing_surfacesGuidance_asReadOnly() {
        given()
            .when().get("/api/scopes/global/entries")
            .then()
                .statusCode(200)
                .body("key", hasItems(KEY_TYPES, KEY_WRITING, KEY_READING))
                // Stage F: built-in guidance carries the system channel and is
                // flagged read-only for the console.
                .body("find { it.key == '" + KEY_TYPES + "' }.readOnly", equalTo(true))
                .body("find { it.key == '" + KEY_TYPES + "' }.source", equalTo("system"));
    }

    @Test
    @TestSecurity(user = "member-reader", roles = {"member"})
    void scopesListing_globalEntryCount_countsGuidance() {
        given()
            .when().get("/api/scopes")
            .then()
                .statusCode(200)
                .body("find { it.slug == 'global' }.entryCount", greaterThanOrEqualTo(3));
    }

    @Test
    @TestSecurity(user = "member-reader", roles = {"member"})
    void overview_totalsCountGuidance() {
        given()
            .when().get("/api/overview")
            .then()
                .statusCode(200)
                .body("entriesTotal", greaterThanOrEqualTo(3))
                .body("entriesByType.convention", greaterThanOrEqualTo(3));
    }
}
