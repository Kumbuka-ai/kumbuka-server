package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.SessionView;
import ai.kumbuka.admin.dto.AdminDtos.UpdateMeRequest;
import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.domain.UserAccount;
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

import java.util.Set;

/**
 * Session info for the admin UI (D2 — account = link-out hybrid). Exposes
 * the caller's identity + role + a link to the Keycloak account console
 * for password / MFA / passkey / sessions management. The only in-app
 * editable field is the display name.
 */
@TenantBound
@Path("/api/auth/me")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SessionResource {

    @Inject SecurityIdentity identity;
    @Inject MemoryConfig config;

    /** UI languages the console ships translations for. */
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "de");

    @GET
    @Authenticated
    public SessionView me() {
        String subject = identity.getPrincipal().getName();
        String email = attr("email");
        UserAccount account = UserAccount.find("subject = ?1", subject).firstResult();
        // displayName fallback: the in-app name, then the Keycloak profile name,
        // then preferred_username, then email. Never the raw sub — the console
        // chrome must render a human label, not a UUID (D-CORE-12).
        String displayName = firstNonBlank(
            account != null ? account.displayName : null,
            attr("name"),
            attr("preferred_username"),
            email);
        boolean muted = account != null && Boolean.TRUE.equals(account.muted);   // D-CORE-2
        String role = identity.getRoles().contains("admin") ? "admin" : "member";
        String accountConsoleUrl = config.authBaseUrl()
            + "/realms/" + config.realm() + "/account";
        String locale = account != null ? account.locale : null;
        return new SessionView(subject, email, displayName, role, accountConsoleUrl, muted, locale);
    }

    @PATCH
    @Authenticated
    @Transactional
    public SessionView updateMe(UpdateMeRequest req) {
        String subject = identity.getPrincipal().getName();
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (u != null && req.displayName() != null) {
            u.displayName = req.displayName().trim();
        }
        if (u != null && req.locale() != null) {
            String loc = req.locale().trim().toLowerCase(java.util.Locale.ROOT);
            if (!SUPPORTED_LOCALES.contains(loc)) {
                throw new BadRequestException("unsupported locale: " + req.locale());
            }
            u.locale = loc;
        }
        // Phase 8 will sync the change to Keycloak via the Admin REST client.
        return me();
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
