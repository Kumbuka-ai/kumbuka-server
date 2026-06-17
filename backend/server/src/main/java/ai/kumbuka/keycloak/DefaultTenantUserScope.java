package ai.kumbuka.keycloak;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

/**
 * OSS single-tenant {@link TenantUserScope}: every realm user is the team, so
 * listing is unrestricted and there is no per-user guard or tenant stamp.
 *
 * <p>{@code @DefaultBean} — the SaaS edition (saas-runtime) supplies a
 * non-default {@code TenantUserScope} that wins via Quarkus ArC precedence,
 * exactly like {@code DefaultSingleTenantResolver} vs the request-aware resolver.
 */
@DefaultBean
@ApplicationScoped
public class DefaultTenantUserScope implements TenantUserScope {

    @Override
    public List<UserRepresentation> listUsers(RealmResource realm) {
        return realm.users().list();
    }

    @Override
    public void assertVisible(RealmResource realm, UserRepresentation user) {
        // single tenant: every user is visible to the (single) team's admins.
    }

    @Override
    public void enrolNewUser(RealmResource realm, String keycloakUserId) {
        // single tenant: no Organization to enrol into.
    }
}
