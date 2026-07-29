package ai.kumbuka.admin;

import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import ai.kumbuka.mcp.MuteTestSupport;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the session surface (D2 — account = link-out hybrid):
 *   GET   /api/auth/me — identity + role + Keycloak account-console deeplink
 *   PATCH /api/auth/me — display-name update (only writable field)
 *
 * Authentication is faked via {@link TestSecurity}. {@code @TestSecurity}
 * doesn't populate OIDC claims, so {@code identity.getAttribute("email")}
 * resolves to {@code null} in these tests — that's the same code path
 * users hit before they pick up an IdP email mapping, and the resource
 * tolerates it gracefully (the spec's null-fallback).
 */
@QuarkusTest
class SessionResourceTest {

    // Seeds a real user_account row (RLS'd) so the locale write-path runs — the
    // PATCH only persists locale when the caller's row exists.
    @Inject MuteTestSupport seed;

    // me()/updateMe lazily provision the caller's row and seed the display name
    // from the KC profile. Mock the lookup; the default mock returns null
    // (no profile) so the name falls through, matching the no-claim tests.
    @InjectMock KeycloakAdminService keycloak;

    @BeforeEach
    void resetKeycloakMock() {
        // No stub leak between tests: default findById → null (no KC profile),
        // so the display-name fallback tests see an absent profile.
        Mockito.reset(keycloak);
    }

    @Test
    void me_unauthenticated_returns401() {
        given()
            .when().get("/api/auth/me")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "sub-alice", roles = {"member"})
    void me_asMember_returnsSubjectRoleAndAccountUrl() {
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("sub-alice"))
                .body("role", equalTo("member"))
                // locale is null until the member picks one (no account row here).
                .body("locale", nullValue())
                // accountConsoleUrl is composed from authBaseUrl + /realms/{realm}/account.
                .body("accountConsoleUrl", containsString("/realms/"))
                .body("accountConsoleUrl", containsString("/account"))
                // securityActionUrl is the AIA authorize base: the console appends
                // &redirect_uri=…&kc_action=… for the password/2FA/passkey deep-links.
                // It must carry the login client id + a PKCE challenge (kumbuka-admin
                // enforces S256) so a hand-built authorize request isn't rejected.
                .body("securityActionUrl", containsString("/protocol/openid-connect/auth"))
                .body("securityActionUrl", containsString("client_id=kumbuka-admin"))
                .body("securityActionUrl", containsString("code_challenge_method=S256"))
                .body("securityActionUrl", containsString("code_challenge="));
    }

    @Test
    @TestSecurity(user = "sub-root", roles = {"admin", "member"})
    void me_withAdminRole_isAdminNotMember() {
        // Role precedence: admin wins when both are present (single string in
        // the response, not an array).
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("role", equalTo("admin"));
    }

    @Test
    @TestSecurity(user = "sub-no-email", roles = {"member"})
    void me_withoutEmailClaim_returnsNullEmailAndDisplayName() {
        // displayName walks account.displayName → name → preferred_username →
        // email; @TestSecurity populates no claims and the mocked KC profile is
        // null, so the lazily-provisioned row carries no name and every
        // candidate is absent. The view must serialise both as null (never the
        // raw sub), not propagate "Unknown" or similar.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("email", nullValue())
                .body("displayName", nullValue());
    }

    @Test
    void displayNameFallback_prefersAccountThenNameThenPreferredUsernameThenEmail() {
        // The displayName precedence (a human label, never the sub).
        // @TestSecurity can't inject OIDC claims, so the ordering is pinned
        // directly on the resolver the resource uses.
        assertThat(SessionResource.firstNonBlank("Account Name", "Name Claim", "puser", "e@x"))
            .isEqualTo("Account Name");
        assertThat(SessionResource.firstNonBlank(null, "Name Claim", "puser", "e@x"))
            .isEqualTo("Name Claim");
        assertThat(SessionResource.firstNonBlank(null, "  ", "puser", "e@x"))
            .isEqualTo("puser");
        assertThat(SessionResource.firstNonBlank(null, null, null, "e@x"))
            .isEqualTo("e@x");
        assertThat(SessionResource.firstNonBlank(null, null, null, null)).isNull();
    }

    @Test
    @TestSecurity(user = "sub-patch", roles = {"member"})
    void updateMe_setsDisplayName_thenReadBack() {
        // S018: PATCH now lazily provisions the caller's row (no pre-existing
        // row needed) and the write round-trips — was a silent no-op before.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"displayName": "Patch User"}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("sub-patch"))
                .body("displayName", equalTo("Patch User"));

        // Persisted — a fresh read returns it.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("displayName", equalTo("Patch User"));
    }

    @Test
    @TestSecurity(user = "57ef0fe1-sub", roles = {"member"})
    void me_provisionsRowAndSeedsDisplayNameFromKeycloak() {
        // S018 root case: invited member with NO user_account row. me() must
        // provision the row keyed by the sub and seed the display name from the
        // Keycloak profile (the same source the roster uses) — never the sub.
        when(keycloak.findById(anyString())).thenReturn(
            new KeycloakUser("57ef0fe1-sub", "familie", "familie@wirsinddiealberts.de",
                "Familie", "Albert", "member", "active", Instant.EPOCH));

        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("57ef0fe1-sub"))
                .body("displayName", equalTo("Familie Albert"));
    }

    @Test
    @TestSecurity(user = "sub-backfill", roles = {"member"})
    void me_backfillsBlankDisplayNameFromKeycloak() {
        // Existing row with no name (pre-S018 provisioning) → backfill from KC.
        seed.setMuted("sub-backfill", false);   // creates the row, display_name blank
        when(keycloak.findById(anyString())).thenReturn(
            new KeycloakUser("sub-backfill", "bf", "bf@x", "Back", "Fill", "member", "active", Instant.EPOCH));

        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("displayName", equalTo("Back Fill"));
    }

    @Test
    @TestSecurity(user = "sub-kcdown", roles = {"member"})
    void me_whenKeycloakUnreachable_stillReturns200() {
        // KC lookup is best-effort: a failure must not break the session view.
        when(keycloak.findById(anyString())).thenThrow(new RuntimeException("kc down"));

        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("subject", equalTo("sub-kcdown"))
                .body("displayName", nullValue());
    }

    @Test
    @TestSecurity(user = "sub-x", roles = {"member"})
    void updateMe_nullDisplayName_isTolerated() {
        // displayName=null means "no change" (clearing it is not a supported
        // operation here). The endpoint returns the current session view.
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().patch("/api/auth/me")
            .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "sub-locale", roles = {"member"})
    void updateMe_setsSupportedLocale_thenReadsBack() {
        seed.setMuted("sub-locale", false);   // ensure the account row exists

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"locale": "de"}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("locale", equalTo("de"));

        // Persisted — a fresh read returns it.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("locale", equalTo("de"));
    }

    @Test
    @TestSecurity(user = "sub-badlocale", roles = {"member"})
    void updateMe_unsupportedLocale_returns400() {
        seed.setMuted("sub-badlocale", false);   // row exists, so validation runs

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"locale": "fr"}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(400);
    }

    // --- onboarding-wizard state (V15) -------

    @Test
    @TestSecurity(user = "sub-onb-default", roles = {"member"})
    void me_freshAccount_onboardingDefaultsToNotDismissed() {
        // A fresh account (V15 defaults) must report the wizard as not dismissed,
        // step 0 — so first login opens it.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("onboarding.dismissed", equalTo(false))
                .body("onboarding.lastStep", equalTo(0));
    }

    @Test
    @TestSecurity(user = "sub-onb-rt", roles = {"member"})
    void updateMe_onboardingDismissed_roundTrips() {
        seed.setMuted("sub-onb-rt", false);   // ensure the account row exists

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"onboarding": {"dismissed": true, "lastStep": 2}}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("onboarding.dismissed", equalTo(true))
                .body("onboarding.lastStep", equalTo(2));

        // Persisted — a fresh read returns the dismissed state (survives login).
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("onboarding.dismissed", equalTo(true))
                .body("onboarding.lastStep", equalTo(2));
    }

    @Test
    @TestSecurity(user = "sub-onb-isolation", roles = {"member"})
    void onboarding_isPerUser_otherAccountUnaffected() {
        // Per-user (keyed by KC sub): this account never dismissed, so it still
        // defaults — independent of the sub-onb-rt account that did dismiss.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("onboarding.dismissed", equalTo(false));
    }

    // --- UI presentation settings (V18): typed field, field-wise merge ---------

    @Test
    @TestSecurity(user = "sub-set-default", roles = {"member"})
    void me_freshAccount_settingsPresentAndUnset() {
        // A fresh account carries the settings object with every field unset
        // (null) — the console applies its defaults (surfaces expanded). The
        // object itself is always present, never null, so clients need no
        // existence check.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.connectCollapsed", nullValue())
                .body("settings.navCollapsed", nullValue());
    }

    @Test
    @TestSecurity(user = "sub-set-merge", roles = {"member"})
    void updateMe_settingsPartialUpdates_bothValuesSurvive() {
        // THE merge guarantee: two consecutive partial updates (two open tabs,
        // each saving a different surface) must both survive. A whole-object
        // write would let the second PATCH silently erase the first.
        seed.setMuted("sub-set-merge", false);   // ensure the account row exists

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"navCollapsed": true}}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.navCollapsed", equalTo(true));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"connectCollapsed": true}}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.connectCollapsed", equalTo(true))
                // The field the second call did NOT carry kept its value.
                .body("settings.navCollapsed", equalTo(true));

        // Third surface, same guarantee — the field the construction was
        // built to absorb without a migration.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"scopesCollapsed": true}}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.scopesCollapsed", equalTo(true))
                .body("settings.connectCollapsed", equalTo(true))
                .body("settings.navCollapsed", equalTo(true));

        // Persisted — a fresh read returns all three.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.connectCollapsed", equalTo(true))
                .body("settings.navCollapsed", equalTo(true))
                .body("settings.scopesCollapsed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "sub-set-false", roles = {"member"})
    void updateMe_settingsExplicitFalse_isAWrite_notANoop() {
        // Boolean, not boolean: "set to false" must be distinguishable from
        // "not sent". Collapse then expand — the expand (false) must stick.
        seed.setMuted("sub-set-false", false);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"connectCollapsed": true}}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(200);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"connectCollapsed": false}}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.connectCollapsed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "sub-set-unknown", roles = {"member"})
    void updateMe_settingsUnknownField_returns400() {
        // The boundary is typed: an unknown field is a typed reject — never
        // ignored, never stored. This is what keeps the field from rotting
        // into a heap of dead keys.
        seed.setMuted("sub-set-unknown", false);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"lastSearchTerm": "secret"}}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(400);

        // Nothing was stored by the rejected call.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.connectCollapsed", nullValue())
                .body("settings.navCollapsed", nullValue());
    }

    @Test
    @TestSecurity(user = "sub-set-type", roles = {"member"})
    void updateMe_settingsWrongType_returns400() {
        seed.setMuted("sub-set-type", false);

        // A string is not a boolean — and neither is a coercible one: the
        // strict deserializer rejects "true" as well as "banana".
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"connectCollapsed": "true"}}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(400);

        // A number is not a boolean either (no 0/1 coercion).
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"navCollapsed": 1}}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(400);

        // An object is not a boolean.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"navCollapsed": {"nested": true}}}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "sub-set-isolation", roles = {"member"})
    void settings_arePerUser_otherAccountUnaffected() {
        // Per-user (keyed by KC sub): this account never collapsed anything,
        // independent of the accounts above that did.
        given()
            .when().get("/api/auth/me")
            .then()
                .statusCode(200)
                .body("settings.connectCollapsed", nullValue())
                .body("settings.navCollapsed", nullValue());
    }

    @Test
    @TestSecurity(user = "sub-set-onb", roles = {"member"})
    void updateMe_settingsWrite_leavesOnboardingUntouched() {
        // The settings field rides beside the onboarding columns; writing one
        // must never disturb the other.
        seed.setMuted("sub-set-onb", false);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"onboarding": {"dismissed": true, "lastStep": 3}}
                """)
            .when().patch("/api/auth/me")
            .then().statusCode(200);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"settings": {"navCollapsed": true}}
                """)
            .when().patch("/api/auth/me")
            .then()
                .statusCode(200)
                .body("onboarding.dismissed", equalTo(true))
                .body("onboarding.lastStep", equalTo(3))
                .body("settings.navCollapsed", equalTo(true));
    }
}
