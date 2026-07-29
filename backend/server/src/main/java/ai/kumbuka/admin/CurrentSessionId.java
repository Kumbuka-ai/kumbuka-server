package ai.kumbuka.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.security.Principal;

/**
 * Resolves the Keycloak user-session id ({@code sid}) that backs THIS request.
 *
 * <p>{@code identity.getAttribute("sid")} comes back null over the
 * bearer-token path, so the current-session marker on {@code /api/sessions} was
 * always false and the account UI offered "terminate" on the caller's own
 * session (a confusing no-op from the console's point of view). The reliable
 * source is the {@code sid} claim carried on the OIDC access token itself — the
 * principal is a {@link JsonWebToken} ({@code OidcJwtCallerPrincipal}) and the
 * claim value equals one of the ids returned by
 * {@code KeycloakAdminService.listUserSessions}.
 *
 * <p>Extracted behind this small request-scoped bean so the resource layer stays
 * testable: {@code @InjectMock} it and stub {@link #get()} without needing a
 * real signed token in unit tests.
 */
@RequestScoped
public class CurrentSessionId {

    private static final Logger LOG = Logger.getLogger(CurrentSessionId.class);

    @Inject SecurityIdentity identity;

    /**
     * The {@code sid} of the caller's current session, or {@code null} when it
     * cannot be determined (principal is not a JWT — e.g. a test identity — or
     * the claim is absent).
     */
    public String get() {
        Principal p = identity.getPrincipal();
        if (p instanceof JsonWebToken jwt) {
            Object sid = jwt.getClaim("sid");
            LOG.debugf("current-session sid from token claim: %s", sid);
            return sid == null ? null : sid.toString();
        }
        LOG.debugf("current-session sid unavailable: principal is not a JWT (%s)",
            p == null ? "null" : p.getClass().getSimpleName());
        return null;
    }
}
