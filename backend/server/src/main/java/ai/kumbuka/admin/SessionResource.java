package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.OnboardingState;
import ai.kumbuka.admin.dto.AdminDtos.SessionView;
import ai.kumbuka.admin.dto.AdminDtos.UpdateMeRequest;
import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.domain.UiSettings;
import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.domain.UserStatus;
import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Session info for the admin UI (D2 — account = link-out hybrid). Exposes
 * the caller's identity + role + a link to the Keycloak account console
 * for password / MFA / passkey / sessions management. The only in-app
 * editable field is the display name.
 *
 * <p>Both endpoints are {@code @Transactional} so the {@code app.tenant_id}
 * GUC is bound (TenantDatabaseBinding) — without an open transaction RLS
 * fails closed and hides the caller's own {@code user_account} row, which is
 * exactly what made {@code me()} render the raw sub and the locale/mute state
 * vanish (S018). They also lazily provision the caller's row (keyed by the KC
 * sub) when it's missing — invited members are not pre-synced — so the GET
 * fallback and the PATCH write always have a row to read/write.
 */
@TenantBound
@Transactional
@Path("/api/auth/me")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    @Inject SecurityIdentity identity;
    @Inject MemoryConfig config;
    @Inject KeycloakAdminService keycloak;

    // The OIDC client the console BFF logs in with (the `admin` tenant). The
    // Application-Initiated-Action deep-links MUST reuse this exact client so
    // the user's existing SSO session and the client's registered redirect URIs
    // both apply. Same source of truth as quarkus.oidc.admin.client-id.
    @ConfigProperty(name = "quarkus.oidc.admin.client-id", defaultValue = "kumbuka-admin")
    String adminClientId;

    private static final SecureRandom RNG = new SecureRandom();

    /** UI languages the console ships translations for. */
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "de");

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_MEMBER = "member";

    private String callerRole() {
        return identity.getRoles().contains(ROLE_ADMIN) ? ROLE_ADMIN : ROLE_MEMBER;
    }

    @GET
    @Authenticated
    public SessionView me() {
        String subject = identity.getPrincipal().getName();
        String email = attr("email");
        UserAccount account = ensureAccount(subject);
        // displayName fallback: the in-app name, then the Keycloak profile name,
        // then preferred_username, then email. Never the raw sub — the console
        // chrome must render a human label, not a UUID.
        String displayName = firstNonBlank(
            account.displayName,
            attr("name"),
            attr("preferred_username"),
            email);
        boolean muted = Boolean.TRUE.equals(account.muted);   // per-member mute
        String role = callerRole();
        String accountConsoleUrl = config.authBaseUrl()
            + "/realms/" + config.realm() + "/account";
        return new SessionView(subject, email, displayName, role,
            accountConsoleUrl, securityActionUrl(), muted, account.locale,
            new OnboardingState(
                Boolean.TRUE.equals(account.onboardingDismissed),
                account.onboardingLastStep == null ? 0 : account.onboardingLastStep),
            account.settings == null ? new UiSettings() : account.settings);
    }

    /**
     * Authorize-endpoint base for Keycloak Application Initiated Actions (AIA).
     * The console appends {@code &redirect_uri=<origin>/account&kc_action=<ACTION>}
     * (UPDATE_PASSWORD / CONFIGURE_TOTP / webauthn-register-passwordless) to land
     * the already-authenticated user straight in the password / 2FA / passkey
     * flow instead of the generic account-console signing-in page.
     *
     * <p>The flow is never completed (the returned {@code code} is discarded —
     * the action's effect is the point), but {@code kumbuka-admin} enforces
     * PKCE S256, so a syntactically valid {@code code_challenge} must be present
     * or Keycloak rejects the authorize request. A fresh challenge is minted per
     * call; the verifier is never needed again. No redirect_uri is baked in here —
     * the console supplies its own origin (the backend's public-base-url is the
     * MCP host, not the console host).
     */
    private String securityActionUrl() {
        return config.authBaseUrl()
            + "/realms/" + config.realm()
            + "/protocol/openid-connect/auth"
            + "?client_id=" + adminClientId
            + "&response_type=code"
            + "&scope=openid"
            + "&code_challenge_method=S256"
            + "&code_challenge=" + freshPkceChallenge();
    }

    /** A random S256 PKCE challenge (base64url, no padding) — see {@link #securityActionUrl()}. */
    private static String freshPkceChallenge() {
        byte[] verifier = new byte[32];
        RNG.nextBytes(verifier);
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String verifierStr = url.encodeToString(verifier);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifierStr.getBytes(StandardCharsets.US_ASCII));
            return url.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS — unreachable on any supported JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @PATCH
    @Authenticated
    public SessionView updateMe(UpdateMeRequest req) {
        String subject = identity.getPrincipal().getName();
        UserAccount u = ensureAccount(subject);
        if (req.displayName() != null) {
            u.displayName = req.displayName().trim();
        }
        if (req.locale() != null) {
            String loc = req.locale().trim().toLowerCase(java.util.Locale.ROOT);
            if (!SUPPORTED_LOCALES.contains(loc)) {
                throw new BadRequestException("unsupported locale: " + req.locale());
            }
            u.locale = loc;
        }
        // persist the onboarding-wizard state (per-user, by KC sub).
        // Both dismiss paths from the console — "don't show again" and completing
        // the wizard — send dismissed=true here so it survives the next login.
        if (req.onboarding() != null) {
            u.onboardingDismissed = req.onboarding().dismissed();
            u.onboardingLastStep = (short) Math.max(0, req.onboarding().lastStep());
        }
        // UI presentation settings: field-wise merge, never replace — a call
        // that sets only one field leaves the others untouched (two open tabs
        // saving different surfaces must not erase each other). Unknown or
        // wrong-typed fields never reach this line: UiSettings rejects them
        // at deserialization, which surfaces as 400.
        if (req.settings() != null) {
            u.settings = UiSettings.merge(u.settings, req.settings());
        }
        // Phase 8 will sync the display-name change back to Keycloak via the Admin REST client.
        return me();
    }

    /**
     * Resolve the caller's {@link UserAccount}, lazily provisioning it — keyed
     * by the KC {@code sub} — when it's absent (invited members are not
     * pre-synced; S018). Seeds the cached display name + email from the
     * Keycloak profile (the same source the team roster uses) so the chrome
     * shows a human name, never the sub; backfills an existing row whose
     * display name is still blank. Must run inside a transaction (set by the
     * callers' {@code @Transactional}) so the GUC is bound and RLS admits the
     * row on read/insert.
     */
    private UserAccount ensureAccount(String subject) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (u == null) {
            return provision(subject);
        }
        if (u.displayName == null || u.displayName.isBlank()) {
            // Existing row never got a name — backfill from the KC profile.
            String name = keycloakName(subject);
            if (name != null) {
                u.displayName = name;
            }
        }
        return u;
    }

    private UserAccount provision(String subject) {
        KeycloakUser ku = lookupKeycloak(subject);
        UserAccount u = new UserAccount();
        u.subject = subject;
        // email is NOT NULL; prefer the token claim, then the KC profile. The
        // subject is a last-resort placeholder for the degenerate case where
        // neither is present (real members always carry an email).
        u.email = firstNonBlank(attr("email"), ku != null ? ku.email() : null, subject);
        u.role = callerRole();
        u.status = UserStatus.fromDb(ku != null && ku.status() != null ? ku.status() : "active");
        u.displayName = ku != null ? blankToNull(fullName(ku)) : null;
        u.persist();
        return u;
    }

    /** Composed first+last name from the KC profile, or null when absent/unreachable. */
    private String keycloakName(String subject) {
        KeycloakUser ku = lookupKeycloak(subject);
        return ku == null ? null : blankToNull(fullName(ku));
    }

    /** Best-effort KC profile lookup; never fails the request if KC is unreachable. */
    private KeycloakUser lookupKeycloak(String subject) {
        try {
            return keycloak.findById(subject);
        } catch (RuntimeException e) {
            LOG.debugf("KC profile lookup failed for %s: %s", subject, e.getMessage());
            return null;
        }
    }

    private static String fullName(KeycloakUser ku) {
        return ((ku.firstName() == null ? "" : ku.firstName()) + " "
              + (ku.lastName() == null ? "" : ku.lastName())).trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String attr(String name) {
        Object v = identity.getAttribute(name);
        return v == null ? null : v.toString();
    }

    /** First non-null, non-blank value, or null when all are absent. */
    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
