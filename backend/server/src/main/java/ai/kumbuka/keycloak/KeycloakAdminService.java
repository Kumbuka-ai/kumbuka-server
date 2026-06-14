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

import java.time.Instant;
import java.util.List;

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
        return realm().users().list().stream()
            .map(this::toView)
            .toList();
    }

    public KeycloakUser findById(String id) {
        UserRepresentation u = realm().users().get(id).toRepresentation();
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

        // Realm role assignment
        if (role != null) {
            assignRealmRole(id, role);
        }

        // Trigger enrolment: send the user a magic link to set a password.
        try {
            realm().users().get(id).executeActionsEmail(List.of("UPDATE_PASSWORD"));
        } catch (Exception ex) {
            LOG.warnf(ex, "executeActionsEmail failed for new user %s — user is created but no email was sent", id);
        }

        return findById(id);
    }

    public void updateRole(String id, String newRole) {
        UserResource user = realm().users().get(id);
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
        rep.setEnabled(enabled);
        user.update(rep);
    }

    // ---- Member session self-management (D-CORE-8) ------------------------

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
