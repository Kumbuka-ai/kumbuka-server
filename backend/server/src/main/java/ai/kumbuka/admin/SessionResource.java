package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.SessionView;
import ai.kumbuka.admin.dto.AdminDtos.UpdateMeRequest;
import ai.kumbuka.config.MemoryConfig;
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
import org.jboss.logging.Logger;

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

    /** UI languages the console ships translations for. */
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "de");

    @GET
    @Authenticated
    public SessionView me() {
        String subject = identity.getPrincipal().getName();
        String email = attr("email");
        UserAccount account = ensureAccount(subject);
        // displayName fallback: the in-app name, then the Keycloak profile name,
        // then preferred_username, then email. Never the raw sub — the console
        // chrome must render a human label, not a UUID (D-CORE-12).
        String displayName = firstNonBlank(
            account.displayName,
            attr("name"),
            attr("preferred_username"),
            email);
        boolean muted = Boolean.TRUE.equals(account.muted);   // D-CORE-2
        String role = identity.getRoles().contains("admin") ? "admin" : "member";
        String accountConsoleUrl = config.authBaseUrl()
            + "/realms/" + config.realm() + "/account";
        return new SessionView(subject, email, displayName, role, accountConsoleUrl, muted, account.locale);
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
            KeycloakUser ku = lookupKeycloak(subject);
            u = new UserAccount();
            u.subject = subject;
            // email is NOT NULL; prefer the token claim, then the KC profile.
            // The subject is a last-resort placeholder for the degenerate case
            // where neither is present (real members always carry an email).
            u.email = firstNonBlank(attr("email"), ku != null ? ku.email() : null, subject);
            u.role = identity.getRoles().contains("admin") ? "admin" : "member";
            u.status = UserStatus.fromDb(ku != null && ku.status() != null ? ku.status() : "active");
            u.displayName = ku != null ? blankToNull(fullName(ku)) : null;
            u.persist();
        } else if (u.displayName == null || u.displayName.isBlank()) {
            // Existing row never got a name — backfill from the KC profile.
            KeycloakUser ku = lookupKeycloak(subject);
            if (ku != null) {
                String name = blankToNull(fullName(ku));
                if (name != null) {
                    u.displayName = name;
                }
            }
        }
        return u;
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
