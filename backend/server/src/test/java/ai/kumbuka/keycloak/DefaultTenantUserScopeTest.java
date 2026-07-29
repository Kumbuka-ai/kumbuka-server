package ai.kumbuka.keycloak;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The OSS single-tenant {@link DefaultTenantUserScope} is a pass-through:
 * listing returns the whole realm, there is no visibility guard, and there is
 * no tenant enrolment. These pin that contract so the OSS edition can never
 * silently turn into a partial tenant filter (the SaaS edition supplies its
 * own membership-scoped implementation).
 */
class DefaultTenantUserScopeTest {

    private final DefaultTenantUserScope scope = new DefaultTenantUserScope();

    @Test
    void listUsers_returnsTheWholeRealmUnfiltered() {
        RealmResource realm = mock(RealmResource.class);
        UsersResource users = mock(UsersResource.class);
        UserRepresentation u = new UserRepresentation();
        when(realm.users()).thenReturn(users);
        when(users.list()).thenReturn(List.of(u));

        assertThat(scope.listUsers(realm)).containsExactly(u);
    }

    @Test
    void assertVisible_andEnrolNewUser_areNoOps() {
        RealmResource realm = mock(RealmResource.class);

        assertThatCode(() -> scope.assertVisible(realm, new UserRepresentation()))
            .doesNotThrowAnyException();
        assertThatCode(() -> scope.enrolNewUser(realm, "any-id"))
            .doesNotThrowAnyException();

        // OSS edition touches no Organization / user API for the guard or enrol.
        verifyNoInteractions(realm);
    }
}
