package ai.kumbuka.admin;

import ai.kumbuka.admin.dto.AdminDtos.ActiveSessionView;
import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakSession;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Member session self-management. A member sees and terminates
 * their OWN active connections to Kumbuka — the browser console session and
 * any connector (MCP) sessions — labelled by OAuth client.
 *
 * <p>Hard scope: every operation is bound to {@code subject == caller}
 * ({@link SecurityIdentity#getPrincipal()}). The caller's Keycloak subject
 * IS the Keycloak user id, so it is used directly to address the admin API;
 * a member can neither list nor terminate another member's sessions. The
 * terminate path verifies ownership before acting and returns 404 (not 403)
 * for an unknown/foreign id so it never leaks whether a session exists.
 *
 * <p>The current session is marked from the access token's {@code sid} claim
 * ({@link CurrentSessionId} — the identity attribute is null over the
 * bearer path). The UI hides "terminate" on the current row and offers
 * "sign out all other sessions" instead ({@link #logoutOthers()}).
 *
 * <p>No {@code @TenantBound}: this resource touches Keycloak only, never the
 * tenant-scoped database, so it needs no RLS GUC. The {@code admin} OIDC
 * tenant (path {@code /api/**}) still authenticates the request.
 */
@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
public class SessionsResource {

    @Inject SecurityIdentity identity;
    @Inject KeycloakAdminService keycloak;
    @Inject CurrentSessionId currentSession;

    @GET
    @Authenticated
    public List<ActiveSessionView> list() {
        String subject = identity.getPrincipal().getName();
        String currentSid = currentSession.get(); // sid claim
        return keycloak.listUserSessions(subject).stream()
            .map(s -> new ActiveSessionView(
                s.id(), s.ipAddress(), s.start(), s.lastAccess(),
                s.rememberMe(), s.clients(), s.id().equals(currentSid)))
            .toList();
    }

    @DELETE
    @Path("/{id}")
    @Authenticated
    public Response terminate(@PathParam("id") String id) {
        String subject = identity.getPrincipal().getName();
        boolean owned = keycloak.listUserSessions(subject).stream()
            .anyMatch(s -> s.id().equals(id));
        if (!owned) {
            // 404, not 403: never confirm a session id that isn't the caller's.
            throw new NotFoundException("session not found");
        }
        keycloak.logoutSession(id);
        return Response.noContent().build();
    }

    /**
     * Terminates every session of the caller EXCEPT the one backing this
     * request. Fails loud with 409 when the current session cannot be
     * identified (no {@code sid}, or it does not match any listed session) —
     * we never silently fall back to "log out everything including me", which
     * would sign the operator out of the very console they clicked from and
     * defeat the "keep this device" intent.
     */
    @POST
    @Path("/logout-others")
    @Authenticated
    public Response logoutOthers() {
        String subject = identity.getPrincipal().getName();
        String currentSid = currentSession.get();
        List<KeycloakSession> sessions = keycloak.listUserSessions(subject);
        boolean currentKnown = currentSid != null
            && sessions.stream().anyMatch(s -> s.id().equals(currentSid));
        if (!currentKnown) {
            throw new WebApplicationException(
                "current session could not be identified", 409);
        }
        sessions.stream()
            .map(KeycloakSession::id)
            .filter(id -> !id.equals(currentSid))
            .forEach(keycloak::logoutSession);
        return Response.noContent().build();
    }
}
