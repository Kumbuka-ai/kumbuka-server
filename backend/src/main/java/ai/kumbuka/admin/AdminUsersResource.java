package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Team-member management — proxies the backend's Keycloak service account
 * (kumbuka-backend). The frontend never talks to Keycloak directly; user
 * provisioning happens here so {@code manage-users} stays scoped to the
 * single confidential client.
 */
@TenantBound
@Transactional
@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminUsersResource {

    @Inject KeycloakAdminService keycloak;

    public record UserView(
        String id,
        String email,
        String firstName,
        String lastName,
        String role,
        String status
    ) {
        public static UserView from(KeycloakUser u) {
            return new UserView(u.id(), u.email(), u.firstName(), u.lastName(), u.role(), u.status());
        }
    }

    public record InviteRequest(String email, String firstName, String lastName, String role) {}
    public record UpdateUserRequest(String role, Boolean enabled) {}

    @GET
    @RolesAllowed({"admin", "member"})
    public List<UserView> list() {
        return keycloak.listUsers().stream().map(UserView::from).toList();
    }

    @POST
    @RolesAllowed("admin")
    public Response invite(InviteRequest req) {
        if (req.email() == null || req.email().isBlank()) {
            throw new BadRequestException("email is required");
        }
        String role = req.role() == null ? "member" : req.role();
        if (!"member".equals(role) && !"admin".equals(role)) {
            throw new BadRequestException("role must be 'member' or 'admin'");
        }
        KeycloakUser created = keycloak.invite(
            req.email().trim(),
            req.firstName(),
            req.lastName(),
            role
        );
        return Response.status(Response.Status.CREATED)
            .entity(UserView.from(created))
            .build();
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("admin")
    public UserView update(@PathParam("id") String id, UpdateUserRequest req) {
        if (req.role() != null) {
            if (!"member".equals(req.role()) && !"admin".equals(req.role())) {
                throw new BadRequestException("role must be 'member' or 'admin'");
            }
            keycloak.updateRole(id, req.role());
        }
        if (req.enabled() != null) {
            keycloak.updateEnabled(id, req.enabled());
        }
        return UserView.from(keycloak.findById(id));
    }
}
