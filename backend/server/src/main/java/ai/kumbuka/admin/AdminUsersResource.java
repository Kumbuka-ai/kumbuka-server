package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.audit.TeamAuditService;
import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.domain.UserStatus;
import ai.kumbuka.erasure.MemberErasureService;
import ai.kumbuka.keycloak.KeycloakAdminService;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(AdminUsersResource.class);

    @Inject KeycloakAdminService keycloak;
    @Inject SecurityIdentity identity;
    @Inject MemberErasureService erasure;
    @Inject TeamAuditService audit;

    private static final String ROLE_MEMBER = "member";
    private static final String ROLE_ADMIN = "admin";
    private static final String STATUS_INVITED = "invited";

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

    /** Typed-confirm gate: the admin echoes the member's email verbatim. */
    public record EraseRequest(String typedConfirm) {}

    public record EraseResult(
        String id,
        String email,
        int privatePurged,
        int sharedTombstoned,
        int scopesTombstoned,
        boolean keycloakRemoved
    ) {}

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

    // ---------- member erasure (D-OPS-16 rev., team-admin primary path) -------

    /**
     * Permanently erase a member (GDPR Art. 17): purge their private memory,
     * tombstone their shared/global authorship to {@code __former-member__},
     * delete the Keycloak user, and write a governance-audit row under the
     * acting admin. Distinct from the reversible disable (PATCH enabled=false).
     *
     * <p>Friction + safety: a typed-confirm matching the member's email, plus
     * guards against erasing yourself or the last remaining admin. The console
     * never sees private content — the purge is by subject (purge ≠ read, P1).
     */
    @POST
    @Path("/{id}/erase")
    @RolesAllowed("admin")
    public EraseResult erase(@PathParam("id") String id, EraseRequest req) {
        final String actor = identity.getPrincipal().getName();
        final KeycloakUser target = keycloak.findById(id);   // 404 if cross-tenant/unknown

        if (id.equals(actor)) {
            throw new BadRequestException("an admin cannot erase their own account");
        }
        if (ROLE_ADMIN.equals(target.role()) && isLastAdmin()) {
            throw new BadRequestException("cannot erase the last remaining admin");
        }
        if (req == null || req.typedConfirm() == null
                || !req.typedConfirm().trim().equalsIgnoreCase(safe(target.email()))) {
            throw new BadRequestException("typedConfirm must match the member's email");
        }

        // Content purge first (the lawful basis). The engine is idempotent and
        // strict-equality-matches on the KC sub (D-CORE-12).
        final MemberErasureService.EraseResult purged = erasure.eraseSubject(id);

        // Keycloak delete is best-effort: if it fails the content is already
        // gone, so we report keycloakRemoved=false rather than undo the purge.
        boolean keycloakRemoved = true;
        try {
            keycloak.deleteUser(id);
        } catch (RuntimeException ex) {
            keycloakRemoved = false;
            LOG.warnf(ex, "member erasure: content purged but Keycloak delete failed for %s", id);
        }

        audit.append(actor, "member.erase", id, Map.of(
            "email", safe(target.email()),
            "privatePurged", purged.privatePurged(),
            "sharedTombstoned", purged.sharedTombstoned(),
            "scopesTombstoned", purged.scopesTombstoned(),
            "keycloakRemoved", keycloakRemoved));

        return new EraseResult(id, target.email(),
            purged.privatePurged(), purged.sharedTombstoned(), purged.scopesTombstoned(),
            keycloakRemoved);
    }

    // ---------- invite lifecycle (re-invite / cancel pending) ----------------

    /** Re-send the enrolment email for a member still in {@code invited} status. */
    @POST
    @Path("/{id}/resend-invite")
    @Consumes(MediaType.WILDCARD)   // bodyless action; don't require a JSON content-type
    @RolesAllowed("admin")
    public Response resendInvite(@PathParam("id") String id) {
        KeycloakUser u = keycloak.findById(id);
        if (!STATUS_INVITED.equals(u.status())) {
            throw new BadRequestException("member is not in 'invited' status");
        }
        keycloak.resendInvite(id);
        return Response.noContent().build();
    }

    /**
     * Cancel/revoke a pending invite — deletes the never-accepted Keycloak user.
     * Only valid while the member is still {@code invited} (409 otherwise); a
     * member who has logged in carries data and must go through {@link #erase}.
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed("admin")
    public Response cancelInvite(@PathParam("id") String id) {
        final String actor = identity.getPrincipal().getName();
        KeycloakUser u = keycloak.findById(id);
        if (!STATUS_INVITED.equals(u.status())) {
            throw new ClientErrorException(
                "cancel-invite is only valid for a pending invite; use erase for an active member",
                Response.Status.CONFLICT);
        }
        keycloak.deleteUser(id);
        audit.append(actor, "member.invite_cancel", id, Map.of("email", safe(u.email())));
        return Response.noContent().build();
    }

    /** True when there is at most one admin left in the tenant. */
    private boolean isLastAdmin() {
        long admins = keycloak.listUsers().stream()
            .filter(u -> ROLE_ADMIN.equals(u.role()))
            .count();
        return admins <= 1;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
