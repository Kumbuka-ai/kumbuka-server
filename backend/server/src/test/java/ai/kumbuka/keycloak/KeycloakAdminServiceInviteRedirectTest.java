package ai.kumbuka.keycloak;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When {@code kumbuka.console-base-url} is configured (the SaaS edition), the
 * invite / resend email must carry the console as {@code redirect_uri} (with
 * the {@code kumbuka-admin} client) so the user lands back on the console after
 * setting their password — not on Keycloak's generic confirmation page.
 */
@QuarkusTest
@TestProfile(KeycloakAdminServiceInviteRedirectTest.WithConsoleUrl.class)
class KeycloakAdminServiceInviteRedirectTest {

    public static class WithConsoleUrl implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.console-base-url", "https://console.test/");
        }
    }

    @Inject KeycloakAdminService svc;
    @InjectMock Keycloak keycloak;
    @InjectMock TenantUserScope scope;

    private UsersResource users;

    @BeforeEach
    void setUp() {
        RealmResource realm = mock(RealmResource.class);
        users = mock(UsersResource.class);
        when(keycloak.realm(anyString())).thenReturn(realm);
        when(realm.users()).thenReturn(users);
    }

    @Test
    void resendInvite_sendsEmailWithConsoleRedirect() {
        UserResource ur = mock(UserResource.class);
        UserRepresentation rep = new UserRepresentation();
        rep.setId("u1");
        when(ur.toRepresentation()).thenReturn(rep);
        when(users.get("u1")).thenReturn(ur);

        svc.resendInvite("u1");

        // redirect form: (clientId, redirectUri, actions) — not the bare actions overload
        verify(ur).executeActionsEmail("kumbuka-admin", "https://console.test/", List.of("UPDATE_PASSWORD"));
    }
}
