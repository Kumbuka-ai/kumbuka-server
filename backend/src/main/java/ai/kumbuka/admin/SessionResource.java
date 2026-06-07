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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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

    @GET
    @Authenticated
    public SessionView me() {
        String subject = identity.getPrincipal().getName();
        String email = attr("email");
        String displayName = readDisplayName(subject, email);
        String role = identity.getRoles().contains("admin") ? "admin" : "member";
        String accountConsoleUrl = config.authBaseUrl()
            + "/realms/" + config.realm() + "/account";
        return new SessionView(subject, email, displayName, role, accountConsoleUrl);
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
        // Phase 8 will sync the change to Keycloak via the Admin REST client.
        return me();
    }

    private String attr(String name) {
        Object v = identity.getAttribute(name);
        return v == null ? null : v.toString();
    }

    private String readDisplayName(String subject, String emailFallback) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (u != null && u.displayName != null) {
            return u.displayName;
        }
        return emailFallback;
    }
}
