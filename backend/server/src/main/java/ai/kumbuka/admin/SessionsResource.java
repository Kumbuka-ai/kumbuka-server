package ai.kumbuka.admin;

import ai.kumbuka.admin.dto.AdminDtos.ActiveSessionView;
import ai.kumbuka.keycloak.KeycloakAdminService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Member session self-management (D-CORE-8). A member sees and terminates
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
 * <p>No {@code @TenantBound}: this resource touches Keycloak only, never the
 * tenant-scoped database, so it needs no RLS GUC. The {@code admin} OIDC
 * tenant (path {@code /api/**}) still authenticates the request.
 */
@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
public class SessionsResource {

    @Inject SecurityIdentity identity;
    @Inject KeycloakAdminService keycloak;

    @GET
    @Authenticated
    public List<ActiveSessionView> list() {
        String subject = identity.getPrincipal().getName();
        String currentSid = attr("sid"); // best-effort marker for this request's session
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

    private String attr(String name) {
        Object v = identity.getAttribute(name);
        return v == null ? null : v.toString();
    }
}
