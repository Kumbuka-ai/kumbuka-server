package ai.kumbuka.keycloak;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakAdminException;
import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link KeycloakAdminService} via Quarkus CDI so quarkus-jacoco
 * records the bytecode hits (a plain JUnit test wouldn't show up in coverage —
 * Quarkus reloads classes through its own classloader). The {@link Keycloak}
 * admin client and {@link MemoryConfig} are mocked at the CDI layer; the
 * real service is injected and exercised.
 */
@QuarkusTest
class KeycloakAdminServiceTest {

    @Inject KeycloakAdminService svc;
    @InjectMock Keycloak keycloak;
    @InjectMock MemoryConfig config;

    private RealmResource realm;
    private UsersResource users;
    private RolesResource roles;
    private ClientsResource clients;

    @BeforeEach
    void setUp() {
        realm = mock(RealmResource.class);
        users = mock(UsersResource.class);
        roles = mock(RolesResource.class);
        clients = mock(ClientsResource.class);

        when(keycloak.realm(anyString())).thenReturn(realm);
        when(realm.users()).thenReturn(users);
        when(realm.roles()).thenReturn(roles);
        when(realm.clients()).thenReturn(clients);

        when(config.realm()).thenReturn("kumbuka");
    }

    private static UserRepresentation rep(String id, String email, Boolean enabled, Boolean emailVerified, Long created) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(email);
        u.setEmail(email);
        u.setFirstName("First");
        u.setLastName("Last");
        u.setEnabled(enabled);
        u.setEmailVerified(emailVerified);
        u.setCreatedTimestamp(created);
        return u;
    }

    private void stubUserResource(String id, UserResource ur) {
        when(users.get(id)).thenReturn(ur);
    }

    /** A user resource whose role listAll returns the given role names. */
    private UserResource userWithRoles(UserRepresentation rep, String... roleNames) {
        UserResource ur = mock(UserResource.class);
        when(ur.toRepresentation()).thenReturn(rep);

        RoleMappingResource rmr = mock(RoleMappingResource.class);
        RoleScopeResource rsr = mock(RoleScopeResource.class);
        when(ur.roles()).thenReturn(rmr);
        when(rmr.realmLevel()).thenReturn(rsr);

        List<RoleRepresentation> reps = java.util.Arrays.stream(roleNames)
            .map(n -> { RoleRepresentation r = new RoleRepresentation(); r.setName(n); return r; })
            .toList();
        when(rsr.listAll()).thenReturn(reps);

        return ur;
    }

    // ---------- toView (status derivation, role derivation) -------------------

    @Test
    void toView_disabledUser_isMarkedDisabled() {
        UserRepresentation u = rep("k1", "alice@x", false, true, 1_700_000_000_000L);
        UserResource ur = userWithRoles(u, "member");
        stubUserResource("k1", ur);
        when(users.list()).thenReturn(List.of(u));

        KeycloakUser view = svc.listUsers().get(0);

        assertThat(view.status()).isEqualTo("disabled");
        assertThat(view.role()).isEqualTo("member");
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.email()).isEqualTo("alice@x");
    }

    @Test
    void toView_unverifiedEmail_isMarkedInvited() {
        UserRepresentation u = rep("k2", "bob@x", true, false, 1L);
        UserResource ur = userWithRoles(u, "member");
        stubUserResource("k2", ur);
        when(users.list()).thenReturn(List.of(u));

        assertThat(svc.listUsers().get(0).status()).isEqualTo("invited");
    }

    @Test
    void toView_enabledAndVerified_isActive() {
        UserRepresentation u = rep("k3", "carol@x", true, true, 2L);
        UserResource ur = userWithRoles(u, "admin", "uma_authorization");
        stubUserResource("k3", ur);
        when(users.list()).thenReturn(List.of(u));

        KeycloakUser view = svc.listUsers().get(0);

        assertThat(view.status()).isEqualTo("active");
        // The role-mapping filter surfaces admin/member only; uma_authorization is ignored.
        assertThat(view.role()).isEqualTo("admin");
    }

    @Test
    void toView_roleLookupFailure_fallsBackToMember() {
        UserRepresentation u = rep("k4", "dave@x", true, true, 3L);
        UserResource ur = mock(UserResource.class);
        when(ur.toRepresentation()).thenReturn(u);
        when(ur.roles()).thenThrow(new RuntimeException("kc 502"));
        stubUserResource("k4", ur);
        when(users.list()).thenReturn(List.of(u));

        assertThat(svc.listUsers().get(0).role()).isEqualTo("member");
    }

    @Test
    void toView_noCreatedTimestamp_returnsNullCreatedAt() {
        UserRepresentation u = rep("k5", "eve@x", true, true, null);
        UserResource ur = userWithRoles(u, "member");
        stubUserResource("k5", ur);
        when(users.list()).thenReturn(List.of(u));

        assertThat(svc.listUsers().get(0).createdAt()).isNull();
    }

    @Test
    void toView_noKnownRole_defaultsToMember() {
        UserRepresentation u = rep("k6", "frank@x", true, true, 4L);
        UserResource ur = userWithRoles(u /* no admin/member */);
        stubUserResource("k6", ur);
        when(users.list()).thenReturn(List.of(u));

        assertThat(svc.listUsers().get(0).role()).isEqualTo("member");
    }

    // ---------- findById -----------------------------------------------------

    @Test
    void findById_returnsViewForExistingUser() {
        UserRepresentation u = rep("kx", "xenia@x", true, true, 10L);
        UserResource ur = userWithRoles(u, "member");
        stubUserResource("kx", ur);

        KeycloakUser found = svc.findById("kx");
        assertThat(found.id()).isEqualTo("kx");
        assertThat(found.email()).isEqualTo("xenia@x");
    }

    // ---------- invite -------------------------------------------------------

    @Test
    void invite_assignsRoleAndSendsEnrolmentEmail() {
        Response created = Response
            .status(201)
            .location(URI.create("http://kc/admin/realms/kumbuka/users/new-id"))
            .build();
        when(users.create(any(UserRepresentation.class))).thenReturn(created);

        UserRepresentation persisted = rep("new-id", "new@x", true, false, 99L);
        UserResource ur = userWithRoles(persisted, "member");
        stubUserResource("new-id", ur);

        RoleResource roleRes = mock(RoleResource.class);
        RoleRepresentation memberRole = new RoleRepresentation();
        memberRole.setName("member");
        when(roles.get("member")).thenReturn(roleRes);
        when(roleRes.toRepresentation()).thenReturn(memberRole);

        KeycloakUser out = svc.invite("new@x", "New", "Hire", "member");

        assertThat(out.id()).isEqualTo("new-id");
        assertThat(out.status()).isEqualTo("invited");

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(users).create(sent.capture());
        UserRepresentation r = sent.getValue();
        assertThat(r.getEmail()).isEqualTo("new@x");
        assertThat(r.getUsername()).isEqualTo("new@x");
        assertThat(r.isEnabled()).isTrue();
        assertThat(r.isEmailVerified()).isFalse();

        verify(ur.roles().realmLevel()).add(anyList());
        verify(ur).executeActionsEmail(List.of("UPDATE_PASSWORD"));
    }

    @Test
    void invite_keycloak4xx_throwsKeycloakAdminException() {
        Response failed = Response.status(409).build();
        when(users.create(any(UserRepresentation.class))).thenReturn(failed);

        assertThatThrownBy(() -> svc.invite("dup@x", null, null, "member"))
            .isInstanceOf(KeycloakAdminException.class)
            .hasMessageContaining("HTTP 409");

        verify(roles, never()).get(anyString());
    }

    @Test
    void invite_withNullRole_skipsRoleAssignmentButStillCreates() {
        Response created = Response.status(201)
            .location(URI.create("http://kc/users/no-role-id")).build();
        when(users.create(any(UserRepresentation.class))).thenReturn(created);
        UserRepresentation persisted = rep("no-role-id", "x@x", true, false, 1L);
        stubUserResource("no-role-id", userWithRoles(persisted));

        svc.invite("x@x", null, null, null);

        verify(roles, never()).get(anyString());
    }

    @Test
    void invite_emailFailureDoesNotPropagate() {
        Response created = Response.status(201)
            .location(URI.create("http://kc/users/email-fail")).build();
        when(users.create(any(UserRepresentation.class))).thenReturn(created);

        UserRepresentation persisted = rep("email-fail", "ef@x", true, false, 1L);
        UserResource ur = userWithRoles(persisted, "member");
        stubUserResource("email-fail", ur);

        RoleResource rr = mock(RoleResource.class);
        when(roles.get("member")).thenReturn(rr);
        when(rr.toRepresentation()).thenReturn(new RoleRepresentation());

        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
            .when(ur).executeActionsEmail(anyList());

        KeycloakUser out = svc.invite("ef@x", null, null, "member");
        assertThat(out.id()).isEqualTo("email-fail");
    }

    // ---------- updateRole ---------------------------------------------------

    @Test
    void updateRole_removesPreviousAndAddsNew() {
        UserResource ur = mock(UserResource.class);
        RoleMappingResource rmr = mock(RoleMappingResource.class);
        RoleScopeResource rsr = mock(RoleScopeResource.class);
        when(ur.roles()).thenReturn(rmr);
        when(rmr.realmLevel()).thenReturn(rsr);

        RoleRepresentation admin = new RoleRepresentation();
        admin.setName("admin");
        RoleRepresentation umaAuth = new RoleRepresentation();
        umaAuth.setName("uma_authorization");
        when(rsr.listAll()).thenReturn(List.of(admin, umaAuth));

        stubUserResource("u1", ur);

        RoleResource memberRoleRes = mock(RoleResource.class);
        RoleRepresentation memberRep = new RoleRepresentation();
        memberRep.setName("member");
        when(roles.get("member")).thenReturn(memberRoleRes);
        when(memberRoleRes.toRepresentation()).thenReturn(memberRep);

        svc.updateRole("u1", "member");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleRepresentation>> removed = ArgumentCaptor.forClass(List.class);
        verify(rsr).remove(removed.capture());
        assertThat(removed.getValue()).extracting(RoleRepresentation::getName)
            .containsExactly("admin");

        verify(rsr).add(List.of(memberRep));
    }

    @Test
    void updateRole_noPreviousAdminOrMember_skipsRemove() {
        UserResource ur = mock(UserResource.class);
        RoleMappingResource rmr = mock(RoleMappingResource.class);
        RoleScopeResource rsr = mock(RoleScopeResource.class);
        when(ur.roles()).thenReturn(rmr);
        when(rmr.realmLevel()).thenReturn(rsr);
        when(rsr.listAll()).thenReturn(List.of());
        stubUserResource("u2", ur);

        RoleResource rr = mock(RoleResource.class);
        when(roles.get("admin")).thenReturn(rr);
        when(rr.toRepresentation()).thenReturn(new RoleRepresentation());

        svc.updateRole("u2", "admin");

        verify(rsr, never()).remove(anyList());
        verify(rsr).add(anyList());
    }

    // ---------- updateEnabled ------------------------------------------------

    @Test
    void updateEnabled_flipsTheFlagAndUpdates() {
        UserResource ur = mock(UserResource.class);
        UserRepresentation r = rep("u3", "x@x", false, true, 1L);
        when(ur.toRepresentation()).thenReturn(r);
        stubUserResource("u3", ur);

        svc.updateEnabled("u3", true);

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(ur).update(sent.capture());
        assertThat(sent.getValue().isEnabled()).isTrue();
    }

    // ---------- connector client + mask --------------------------------------

    @Test
    void getConnectorSecretMasked_visibleLast4Chars() {
        wireConnectorClient("kumbuka-connector", "abcdef-secret-w123");

        assertThat(svc.getConnectorSecretMasked("kumbuka-connector"))
            .endsWith("w123")
            .startsWith("••");
    }

    @Test
    void getConnectorSecretMasked_nullSecret_returnsNull() {
        wireConnectorClientWithNullSecret("kumbuka-connector");
        assertThat(svc.getConnectorSecretMasked("kumbuka-connector")).isNull();
    }

    @Test
    void rotateConnectorSecret_returnsNewMaskedAndCallsGenerate() {
        ClientResource clientRes = wireConnectorClient("kumbuka-connector", "irrelevant");
        CredentialRepresentation newSecret = new CredentialRepresentation();
        newSecret.setValue("new-rotated-z789");
        when(clientRes.generateNewSecret()).thenReturn(newSecret);

        String masked = svc.rotateConnectorSecret("kumbuka-connector", "actor-sub");
        assertThat(masked).endsWith("z789");
        verify(clientRes).generateNewSecret();
    }

    @Test
    void rotateConnectorSecret_nullGeneratedSecret_returnsNull() {
        ClientResource clientRes = wireConnectorClient("kumbuka-connector", "irrelevant");
        when(clientRes.generateNewSecret()).thenReturn(null);

        assertThat(svc.rotateConnectorSecret("kumbuka-connector", "actor")).isNull();
    }

    @Test
    void connectorClient_notFound_throwsKeycloakAdminException() {
        when(clients.findByClientId("missing")).thenReturn(List.of());

        assertThatThrownBy(() -> svc.getConnectorSecretMasked("missing"))
            .isInstanceOf(KeycloakAdminException.class)
            .hasMessageContaining("connector client not found");
    }

    @Test
    void mask_static_helperEdges() {
        assertThat(KeycloakAdminService.mask(null)).isNull();
        assertThat(KeycloakAdminService.mask("")).isEqualTo("");
        assertThat(KeycloakAdminService.mask("abcd")).isEqualTo("••••");
        assertThat(KeycloakAdminService.mask("abcdef")).isEqualTo("••cdef");
        assertThat(KeycloakAdminService.mask("sk_live_abcdef12")).isEqualTo("••••••••••••ef12");
    }

    // ---------- helpers ------------------------------------------------------

    private ClientResource wireConnectorClient(String clientId, String secretValue) {
        ClientRepresentation crep = new ClientRepresentation();
        crep.setId("uuid-of-" + clientId);
        crep.setClientId(clientId);
        when(clients.findByClientId(clientId)).thenReturn(List.of(crep));

        ClientResource clientRes = mock(ClientResource.class);
        when(clients.get("uuid-of-" + clientId)).thenReturn(clientRes);

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setValue(secretValue);
        when(clientRes.getSecret()).thenReturn(cred);
        return clientRes;
    }

    private void wireConnectorClientWithNullSecret(String clientId) {
        ClientRepresentation crep = new ClientRepresentation();
        crep.setId("uuid-of-" + clientId);
        when(clients.findByClientId(clientId)).thenReturn(List.of(crep));

        ClientResource clientRes = mock(ClientResource.class);
        when(clients.get("uuid-of-" + clientId)).thenReturn(clientRes);
        when(clientRes.getSecret()).thenReturn(null);
    }
}
