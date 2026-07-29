package ai.kumbuka.admin;

import ai.kumbuka.mcp.ScopeLockTestSupport;
import ai.kumbuka.overlay.GuidanceOverlay;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The console single-delete route ({@code DELETE /api/scopes/{slug}/entries/{id}})
 * answers two look-alike deletes differently — 404 versus a typed 409 — and the
 * difference is deliberately whether the id addresses a real row, NOT whether its
 * key is reserved. Both results are the ratified behaviour; this exercises them
 * end-to-end through a real HTTP dispatch and the running database, so a later
 * change that made the route resolve the read-layer id (and answer 409, or 200)
 * turns one of these tests red.
 *
 * <ul>
 *   <li><b>A built-in guidance entry → 404.</b> The built-in entries an assistant
 *       sees are a read layer merged on top of the tenant's rows; they are not
 *       table rows. Their id is synthetic, so the row-addressed delete route
 *       resolves it to nothing and answers "not found". It is not a 409, even
 *       though the entry's key is in the reserved namespace: the route never gets
 *       as far as looking at the key, because there is no row to load. A human in
 *       the console is never offered a delete control on a read-only built-in
 *       entry in the first place; this is the defensive answer if an id for one
 *       is submitted anyway.</li>
 *   <li><b>A real row that carries a reserved key → typed 409.</b> Such a row can
 *       only be planted below the write seam (every caller-facing write to the
 *       reserved namespace is refused), the shape of a legacy seed row. Here the
 *       route loads the row, and the delete is refused with the typed
 *       reserved-namespace conflict. The reservation is stated as a learnable,
 *       typed rejection where a real row is addressed — the read-layer 404 is not
 *       a place to teach the rule, because nothing was there to reserve.</li>
 * </ul>
 *
 * <p>Companion to the mocked route contract in {@link AdminEntriesResourceTest}
 * (which stubs the repository); this drives the real {@link GuidanceOverlay}
 * resolution and a real planted row against the DevServices Postgres, so the
 * 404-vs-409 split is observed at the level it is claimed — the route.
 *
 * <p>Seeds through the tenant-bound {@link ScopeLockTestSupport}; the trust
 * boundary is real. {@code @AfterEach} clears the planted row.
 */
@QuarkusTest
@Tag("integration")
class AdminEntriesReservedDeleteRestIT {

    /** A throwaway project scope (the {@code sl-} prefix is what the support
     *  helper's cleanup targets), so the planted reserved-key row is removed
     *  after the run. */
    private static final String SCOPE = "sl-reserved-del";

    @Inject ScopeLockTestSupport support;
    @Inject GuidanceOverlay guidance;

    @AfterEach
    void cleanup() {
        support.cleanup();
    }

    // ---- a built-in guidance entry (synthetic id, no row) → 404 ----
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void deleteBuiltInGuidanceEntry_bySyntheticId_returns404_notReservedConflict() {
        // The synthetic id an assistant sees in every recall result — its key is
        // reserved, but it addresses no row, so the row-addressed route 404s
        // before the reserved-namespace rule is ever consulted.
        UUID syntheticId = guidance.entries().get(0).logicalId;

        given()
            .when().delete("/api/scopes/global/entries/" + syntheticId)
            .then().statusCode(404);
    }

    // ---- a real row carrying a reserved key (planted below the seam) → typed 409 ----
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void deleteRealRowWithReservedKey_returns409_typedReservedNamespace() {
        // Plant a real reserved-key row below the write seam, then leave the scope
        // open so the ONLY thing that can refuse the delete is the reserved-key
        // rule (not a scope lock). The route loads the row and refuses with the
        // typed reserved-namespace conflict.
        UUID rowId = support.seedSystemEntryThenLock(
            SCOPE, "system.planted.rest-del", "built-in");
        support.setLocked(SCOPE, false);

        given()
            .when().delete("/api/scopes/" + SCOPE + "/entries/" + rowId)
            .then()
                .statusCode(409)
                .body("code", equalTo("PROTECTED_RESERVED_NAMESPACE"));

        // The refusal left the row in place.
        org.assertj.core.api.Assertions.assertThat(support.entryCount(SCOPE))
            .as("the reserved-key row is untouched after the refused delete")
            .isEqualTo(1);
    }
}
