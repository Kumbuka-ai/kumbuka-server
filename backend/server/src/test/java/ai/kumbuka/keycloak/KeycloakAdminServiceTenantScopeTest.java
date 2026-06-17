package ai.kumbuka.keycloak;

import ai.kumbuka.keycloak.KeycloakAdminService.KeycloakUser;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@link KeycloakAdminService} MUST route every user operation through the
 * {@link TenantUserScope} seam — that is what lets the SaaS edition scope the
 * shared Keycloak realm to one tenant. These tests CDI-mock the seam and assert
 * the service consults it (rather than touching {@code realm().users()} raw),
 * which is the security-critical contract behind the cross-tenant fix.
 */
@QuarkusTest
class KeycloakAdminServiceTenantScopeTest {

    @Inject KeycloakAdminService svc;
    @InjectMock Keycloak keycloak;
    @InjectMock TenantUserScope scope;

    private RealmResource realm;
    private UsersResource users;

    @BeforeEach
    void setUp() {
        realm = mock(RealmResource.class);
        users = mock(UsersResource.class);
        when(keycloak.realm(anyString())).thenReturn(realm);
        when(realm.users()).thenReturn(users);
    }

    private UserResource userWithRoles(UserRepresentation rep, String... roleNames) {
        UserResource ur = mock(UserResource.class);
        when(ur.toRepresentation()).thenReturn(rep);
        RoleMappingResource rmr = mock(RoleMappingResource.class);
        RoleScopeResource rsr = mock(RoleScopeResource.class);
        when(ur.roles()).thenReturn(rmr);
        when(rmr.realmLevel()).thenReturn(rsr);
        when(rsr.listAll()).thenReturn(java.util.Arrays.stream(roleNames)
            .map(n -> { RoleRepresentation r = new RoleRepresentation(); r.setName(n); return r; })
            .toList());
        return ur;
    }

    private static UserRepresentation rep(String id, String email) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(email);
        u.setEmail(email);
        u.setEnabled(true);
        return u;
    }

    @Test
    void listUsers_comesFromTheScope_neverTheWholeRealm() {
        UserRepresentation u = rep("alpha-1", "a1@alpha.test");
        when(scope.listUsers(any())).thenReturn(List.of(u));
        // toView resolves the role via realm().users().get(id) — stub that chain.
        UserResource ur = userWithRoles(u, "member");
        when(users.get("alpha-1")).thenReturn(ur);

        List<KeycloakUser> out = svc.listUsers();

        assertThat(out).extracting(KeycloakUser::id).containsExactly("alpha-1");
        verify(scope).listUsers(any());
        verify(users, never()).list();   // the shared realm is NEVER listed unscoped
    }

    @Test
    void findById_isGuardedByAssertVisible() {
        UserRepresentation foreign = rep("beta-9", "b9@beta.test");
        UserResource ur = userWithRoles(foreign, "admin");
        when(users.get("beta-9")).thenReturn(ur);
        doThrow(new NotFoundException("user not found in this tenant"))
            .when(scope).assertVisible(any(), any());

        assertThatThrownBy(() -> svc.findById("beta-9"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateEnabled_isGuardedByAssertVisible_noWriteOnForeignUser() {
        UserRepresentation foreign = rep("beta-9", "b9@beta.test");
        UserResource ur = userWithRoles(foreign, "admin");
        when(users.get("beta-9")).thenReturn(ur);
        doThrow(new NotFoundException("user not found in this tenant"))
            .when(scope).assertVisible(any(), any());

        assertThatThrownBy(() -> svc.updateEnabled("beta-9", false))
            .isInstanceOf(NotFoundException.class);
        verify(ur, never()).update(any());   // the foreign user is never modified
    }
}
