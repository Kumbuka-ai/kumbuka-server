package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.domain.UserStatus;
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
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final String ROLE_MEMBER = "member";
    private static final String ROLE_ADMIN = "admin";

    public record UserView(
        String id,
        String email,
        String firstName,
        String lastName,
        String role,
        String status,
        boolean muted   // D-CORE-2
    ) {
        public static UserView from(KeycloakUser u, boolean muted) {
            return new UserView(u.id(), u.email(), u.firstName(), u.lastName(), u.role(), u.status(), muted);
        }
    }

    public record InviteRequest(String email, String firstName, String lastName, String role) {}
    public record UpdateUserRequest(String role, Boolean enabled, Boolean muted) {}

    @GET
    @RolesAllowed({"admin", "member"})
    public List<UserView> list() {
        Map<String, Boolean> mutedBySubject = mutedMap();
        return keycloak.listUsers().stream()
            .map(u -> UserView.from(u, mutedBySubject.getOrDefault(u.id(), false)))
            .toList();
    }

    /** Tenant-scoped subject → muted map (the Keycloak `sub` is the user id). */
    private Map<String, Boolean> mutedMap() {
        List<UserAccount> rows = UserAccount.listAll();
        return rows.stream().collect(Collectors.toMap(
            a -> a.subject, a -> Boolean.TRUE.equals(a.muted), (a, b) -> a));
    }

    @POST
    @RolesAllowed("admin")
    public Response invite(InviteRequest req) {
        if (req.email() == null || req.email().isBlank()) {
            throw new BadRequestException("email is required");
        }
        String role = req.role() == null ? ROLE_MEMBER : req.role();
        if (!ROLE_MEMBER.equals(role) && !ROLE_ADMIN.equals(role)) {
            throw new BadRequestException("role must be 'member' or 'admin'");
        }
        KeycloakUser created = keycloak.invite(
            req.email().trim(),
            req.firstName(),
            req.lastName(),
            role
        );
        return Response.status(Response.Status.CREATED)
            .entity(UserView.from(created, false))   // freshly invited members are never muted
            .build();
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("admin")
    public UserView update(@PathParam("id") String id, UpdateUserRequest req) {
        if (req.role() != null) {
            if (!ROLE_MEMBER.equals(req.role()) && !ROLE_ADMIN.equals(req.role())) {
                throw new BadRequestException("role must be 'member' or 'admin'");
            }
            keycloak.updateRole(id, req.role());
        }
        if (req.enabled() != null) {
            keycloak.updateEnabled(id, req.enabled());
        }
        KeycloakUser ku = keycloak.findById(id);
        boolean muted = applyMute(id, req.muted(), ku);   // D-CORE-2
        return UserView.from(ku, muted);
    }

    /**
     * D-CORE-2: set/clear the mute flag on the member's {@link UserAccount},
     * lazily creating the row (it mirrors Keycloak and isn't pre-synced). The
     * Keycloak `sub` IS the user id, so it keys the row directly. Returns the
     * effective muted state (current value when {@code muted} is null).
     */
    private boolean applyMute(String subject, Boolean muted, KeycloakUser ku) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (muted == null) {
            return u != null && Boolean.TRUE.equals(u.muted);
        }
        if (u == null) {
            u = new UserAccount();
            u.subject = subject;
            u.email = ku.email();
            u.role = ku.role();
            u.status = UserStatus.fromDb(ku.status());
            u.persist();
        }
        u.muted = muted;
        return muted;
    }
}
