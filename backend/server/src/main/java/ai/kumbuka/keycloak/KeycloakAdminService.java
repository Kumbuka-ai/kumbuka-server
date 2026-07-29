package ai.kumbuka.keycloak;

import ai.kumbuka.config.MemoryConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Thin wrapper around the {@link Keycloak} admin REST client. Centralises
 * realm + authoritative-write semantics so the resource layer stays small
 * and so tests can stub the wrapper without touching Keycloak (real KC
 * integration testing lands in Phase 11).
 */
@ApplicationScoped
public class KeycloakAdminService {

    private static final Logger LOG = Logger.getLogger(KeycloakAdminService.class);

    private static final String ROLE_MEMBER = "member";
    private static final String ROLE_ADMIN = "admin";

    @Inject Keycloak keycloak;
    @Inject MemoryConfig config;
    @Inject TenantUserScope userScope;   // OSS: pass-through. SaaS: scopes users to the caller's tenant.

    // The console's OIDC client; used as the redirect client on the invite /
    // password-setup email action so Keycloak returns the user to the console.
    @ConfigProperty(name = "quarkus.oidc.admin.client-id", defaultValue = "kumbuka-admin")
    String adminClientId;

    private static final List<String> SETUP_PASSWORD = List.of("UPDATE_PASSWORD");

    /**
     * Sends the enrolment / password-setup email (the UPDATE_PASSWORD magic
     * link). When a console base URL is configured, it is passed as the
     * {@code redirect_uri} (with {@code kumbuka-admin} as the client) so the
     * user lands back on the console after setting their password instead of
     * Keycloak's generic "account updated" page. Otherwise (CE single-tenant,
     * no console URL) the plain email is sent and Keycloak shows its own page.
     */
    private void sendPasswordSetupEmail(UserResource user) {
        Optional<String> console = config.consoleBaseUrl().filter(s -> !s.isBlank());
        if (console.isPresent()) {
            user.executeActionsEmail(adminClientId, console.get(), SETUP_PASSWORD);
        } else {
            user.executeActionsEmail(SETUP_PASSWORD);
        }
    }

    private RealmResource realm() {
        return keycloak.realm(config.realm());
    }

    public record KeycloakUser(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        String role,
        String status,           // active | invited | disabled
        Instant createdAt
    ) {}

    public List<KeycloakUser> listUsers() {
        // Tenant-scoped in SaaS — never list the whole shared realm (cross-tenant leak).
        return userScope.listUsers(realm()).stream()
            .map(this::toView)
            .toList();
    }

    public KeycloakUser findById(String id) {
        UserRepresentation u = realm().users().get(id).toRepresentation();
        userScope.assertVisible(realm(), u);   // SaaS: 404 if the user is not in the caller's Org
        return toView(u);
    }

    /**
     * Create a new Keycloak user with no password set, mark as `invited`,
     * trigger the enrolment email (UPDATE_PASSWORD action). The caller's
     * role assignment is applied as part of the same operation.
     */
    public KeycloakUser invite(String email, String firstName, String lastName, String role) {
        UserRepresentation rep = new UserRepresentation();
        rep.setEmail(email);
        rep.setUsername(email);
        rep.setFirstName(firstName);
        rep.setLastName(lastName);
        rep.setEnabled(true);
        rep.setEmailVerified(false);

        String id;
        try (Response response = realm().users().create(rep)) {
            if (response.getStatus() >= 400) {
                throw new KeycloakAdminException(
                    "Keycloak rejected user create: HTTP " + response.getStatus());
            }
            id = CreatedResponseUtil.getCreatedId(response);
        }

        // Bind the new user to the caller's tenant. In SaaS this adds
        // KC-Organization membership, the source of the
        // organization claim — without it the invited member has no tenant
        // binding and the SaaS resolver rejects the session as
        // TOKEN_ORG_MISSING. In OSS it is a no-op. Membership needs the user
        // id, so it runs after create; on failure undo the half-created user
        // rather than leave a tenant-less orphan.
        try {
            userScope.enrolNewUser(realm(), id);
        } catch (RuntimeException e) {
            tryRemoveUser(id);
            throw e;
        }

        // Realm role assignment
        if (role != null) {
            assignRealmRole(id, role);
        }

        // Trigger enrolment: send the user a magic link to set a password.
        try {
            sendPasswordSetupEmail(realm().users().get(id));
        } catch (Exception ex) {
            LOG.warnf(ex, "executeActionsEmail failed for new user %s — user is created but no email was sent", id);
        }

        return findById(id);
    }

    /**
     * Best-effort removal of a half-created user when a later step of {@link #invite}
     * fails (e.g. Org enrolment). Never throws — surfacing the original failure to
     * the caller matters more than a cleanup hiccup; an orphan is logged here.
     * Bypasses the {@code userScope} visibility guard (the user may not yet be a
     * member of any Org, which is exactly the half-created state we are undoing).
     */
    private void tryRemoveUser(String id) {
        try {
            realm().users().get(id).remove();
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "failed to clean up half-created user %s after a failed invite", id);
        }
    }

    public void updateRole(String id, String newRole) {
        UserResource user = realm().users().get(id);
        userScope.assertVisible(realm(), user.toRepresentation());   // SaaS: never role-change another tenant's user
        // Remove all member/admin roles, then add the new one.
        List<RoleRepresentation> current = user.roles().realmLevel().listAll();
        List<RoleRepresentation> toRemove = current.stream()
            .filter(r -> ROLE_MEMBER.equals(r.getName()) || ROLE_ADMIN.equals(r.getName()))
            .toList();
        if (!toRemove.isEmpty()) {
            user.roles().realmLevel().remove(toRemove);
        }
        assignRealmRole(id, newRole);
    }

    public void updateEnabled(String id, boolean enabled) {
        UserResource user = realm().users().get(id);
        UserRepresentation rep = user.toRepresentation();
        userScope.assertVisible(realm(), rep);   // SaaS: never enable/disable another tenant's user
        rep.setEnabled(enabled);
        user.update(rep);
    }

    /**
     * Permanently removes the Keycloak user. Used by the member-erasure
     * orchestration (after the OSS content purge) and by cancel-invite. The
     * tenant-visibility check fires first so an admin can never delete a user
     * in another tenant (SaaS); in the OSS edition it is a pass-through.
     */
    public void deleteUser(String id) {
        UserResource user = realm().users().get(id);
        userScope.assertVisible(realm(), user.toRepresentation());
        user.remove();
    }

    /**
     * Re-sends the enrolment email (the UPDATE_PASSWORD magic link) for a user
     * who is still pending — e.g. an invite the member never accepted. Same
     * action the initial {@link #invite} triggers; safe to call repeatedly.
     */
    public void resendInvite(String id) {
        UserResource user = realm().users().get(id);
        userScope.assertVisible(realm(), user.toRepresentation());
        sendPasswordSetupEmail(user);
    }

    // ---- Member session self-management ------------------------

    public record KeycloakSession(
        String id,
        String ipAddress,
        Instant start,
        Instant lastAccess,
        boolean rememberMe,
        List<String> clients
    ) {}

    /**
     * Lists the (online) Keycloak sessions for one user. The caller MUST pass
     * its own subject — the resource layer enforces {@code subject == caller}
     * so no member can enumerate another's sessions. Reading sessions needs
     * the {@code view-users} realm-management role, already granted to the
     * {@code kumbuka-backend} service account.
     */
    public List<KeycloakSession> listUserSessions(String userId) {
        return realm().users().get(userId).getUserSessions().stream()
            .map(this::toSessionView)
            .toList();
    }

    /**
     * Terminates a single online session by id (Keycloak invalidates the
     * session's tokens, including any with {@code aud=kumbuka-connector-*}).
     * Ownership is NOT re-checked here — the resource layer verifies the
     * session belongs to the caller before calling this. Needs the
     * {@code manage-users} role (already granted to {@code kumbuka-backend}).
     */
    public void logoutSession(String sessionId) {
        realm().deleteSession(sessionId, false);
    }

    // ---- Member credential self-management ----------------------

    /**
     * One of a user's Keycloak credentials, reduced to the display-safe fields.
     * {@code createdDate} is epoch-millis (KC's own representation) or null.
     * Keycloak stores NO "last used" per credential — hence none here.
     */
    public record KeycloakCredential(
        String id,
        String type,
        String userLabel,
        Long createdDate
    ) {}

    /**
     * Lists a user's credentials (all types). The caller MUST pass its own
     * subject — the resource layer enforces {@code subject == caller} so no
     * member can enumerate another's credentials. Reading credentials needs the
     * {@code view-users} realm-management role (already granted to
     * {@code kumbuka-backend}).
     */
    public List<KeycloakCredential> listUserCredentials(String userId) {
        return realm().users().get(userId).credentials().stream()
            .map(c -> new KeycloakCredential(
                c.getId(), c.getType(), c.getUserLabel(), c.getCreatedDate()))
            .toList();
    }

    /**
     * Removes a single credential by id. Ownership + type-eligibility are
     * verified at the resource layer BEFORE this call (mirrors
     * {@link #logoutSession}). Needs the {@code manage-users} role (already
     * granted to {@code kumbuka-backend}).
     */
    public void removeUserCredential(String userId, String credentialId) {
        realm().users().get(userId).removeCredential(credentialId);
    }

    private KeycloakSession toSessionView(UserSessionRepresentation s) {
        List<String> clients = s.getClients() == null
            ? List.of()
            : s.getClients().values().stream().distinct().sorted().toList();
        return new KeycloakSession(
            s.getId(),
            s.getIpAddress(),
            s.getStart() == 0 ? null : Instant.ofEpochMilli(s.getStart()),
            s.getLastAccess() == 0 ? null : Instant.ofEpochMilli(s.getLastAccess()),
            s.isRememberMe(),
            clients
        );
    }

    // ---- Connector client (ADR-0006, ADR-pending Phase 10) ----------------

    /**
     * Returns the connector client's current secret in masked form
     * (last 4 chars visible). Used by the admin Connector card.
     */
    public String getConnectorSecretMasked(String connectorClientId) {
        CredentialRepresentation cred = connectorClient(connectorClientId).getSecret();
        return mask(cred == null ? null : cred.getValue());
    }

    /**
     * Rotates the connector client's secret via Keycloak's regenerateSecret.
     * The old secret is invalidated atomically server-side. Returns the
     * new secret masked. Audit logged.
     */
    public String rotateConnectorSecret(String connectorClientId, String actorSubject) {
        ClientResource client = connectorClient(connectorClientId);
        CredentialRepresentation newCred = client.generateNewSecret();
        LOG.infof("connector secret rotated: client=%s actor=%s",
            connectorClientId, actorSubject);
        return mask(newCred == null ? null : newCred.getValue());
    }

    private ClientResource connectorClient(String connectorClientId) {
        List<ClientRepresentation> matches =
            realm().clients().findByClientId(connectorClientId);
        if (matches.isEmpty()) {
            throw new KeycloakAdminException(
                "connector client not found in Keycloak: " + connectorClientId);
        }
        return realm().clients().get(matches.get(0).getId());
    }

    public static String mask(String secret) {
        if (secret == null) return null;
        int len = secret.length();
        if (len <= 4) return "•".repeat(len);
        return "•".repeat(len - 4) + secret.substring(len - 4);
    }

    // -----------------------------------------------------------------------

    private void assignRealmRole(String userId, String roleName) {
        RoleRepresentation role = realm().roles().get(roleName).toRepresentation();
        realm().users().get(userId).roles().realmLevel().add(List.of(role));
    }

    private KeycloakUser toView(UserRepresentation u) {
        // Status derivation: enabled=false → disabled; enabled=true but
        // emailVerified=false and no recent login → invited (best-effort
        // heuristic without a separate "invited" flag in Keycloak).
        String status;
        if (Boolean.FALSE.equals(u.isEnabled())) {
            status = "disabled";
        } else if (Boolean.FALSE.equals(u.isEmailVerified())) {
            status = "invited";
        } else {
            status = "active";
        }

        String role;
        try {
            role = realm().users().get(u.getId()).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .filter(n -> ROLE_ADMIN.equals(n) || ROLE_MEMBER.equals(n))
                .findFirst().orElse(ROLE_MEMBER);
        } catch (Exception ex) {
            role = ROLE_MEMBER;
        }

        Instant createdAt = u.getCreatedTimestamp() == null
            ? null
            : Instant.ofEpochMilli(u.getCreatedTimestamp());

        return new KeycloakUser(
            u.getId(), u.getUsername(), u.getEmail(),
            u.getFirstName(), u.getLastName(), role, status, createdAt
        );
    }

    public static class KeycloakAdminException extends RuntimeException {
        public KeycloakAdminException(String m) { super(m); }
    }
}
