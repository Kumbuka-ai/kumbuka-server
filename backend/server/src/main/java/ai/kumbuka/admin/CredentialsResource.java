package ai.kumbuka.admin;

import ai.kumbuka.admin.dto.AdminDtos.CredentialView;
import ai.kumbuka.admin.dto.AdminDtos.CredentialsView;
import ai.kumbuka.keycloak.KeycloakAdminService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Member credential self-management — the caller sees and removes
 * their OWN two-factor authenticators and passkeys. Fixes a bug (passkeys were
 * addable via the Keycloak AIA flow but never removable from the console).
 *
 * <p>Hard scope, cloned from {@link SessionsResource}: every operation is bound
 * to {@code subject == caller} ({@link SecurityIdentity#getPrincipal()}). The
 * caller's Keycloak subject IS the Keycloak user id, used directly against the
 * admin API; a member can neither list nor delete another member's credentials.
 * The delete path verifies ownership AND type-eligibility before acting and
 * returns 404 (not 403) for an unknown / foreign / non-self-service id so it
 * never leaks whether a credential exists.
 *
 * <p>Only {@link #SELF_SERVICE_TYPES} are listed and deletable. Other credential
 * types (password, {@code recovery-authn-codes}) are never listed here and a
 * delete request for one 404s like any foreign id.
 *
 * <p>No {@code @TenantBound}: Keycloak-only, never the tenant DB (mirrors
 * {@link SessionsResource}). The {@code admin} OIDC tenant authenticates the
 * request.
 */
@Path("/api/credentials")
@Produces(MediaType.APPLICATION_JSON)
public class CredentialsResource {

    /** The credential types a member may see and remove for themselves. */
    static final Set<String> SELF_SERVICE_TYPES =
        Set.of("otp", "webauthn", "webauthn-passwordless");

    /** Keycloak credential type for recovery codes — presence-only, never listed. */
    static final String RECOVERY_CODES_TYPE = "recovery-authn-codes";

    @Inject SecurityIdentity identity;
    @Inject KeycloakAdminService keycloak;

    @GET
    @Authenticated
    public CredentialsView list() {
        String subject = identity.getPrincipal().getName();
        List<KeycloakAdminService.KeycloakCredential> all = keycloak.listUserCredentials(subject);
        List<CredentialView> credentials = all.stream()
            .filter(c -> SELF_SERVICE_TYPES.contains(c.type()))
            .map(c -> new CredentialView(
                c.id(), c.type(), c.userLabel(),
                c.createdDate() == null ? null : Instant.ofEpochMilli(c.createdDate())))
            .toList();
        // Presence-only: never read or return the codes themselves (they render
        // on Keycloak's own themed AIA page — ratified reconciliation).
        boolean recoveryCodesConfigured = all.stream()
            .anyMatch(c -> RECOVERY_CODES_TYPE.equals(c.type()));
        return new CredentialsView(credentials, recoveryCodesConfigured);
    }

    @DELETE
    @Path("/{id}")
    @Authenticated
    public Response remove(@PathParam("id") String id) {
        String subject = identity.getPrincipal().getName();
        // Ownership AND type-eligibility in one pass: only a self-service-typed
        // credential that belongs to the caller is deletable. Anything else
        // (foreign id, unknown id, or a non-self-service type such as
        // recovery-authn-codes or password) → 404, never 403 and never a leak.
        boolean deletable = keycloak.listUserCredentials(subject).stream()
            .anyMatch(c -> c.id().equals(id) && SELF_SERVICE_TYPES.contains(c.type()));
        if (!deletable) {
            throw new NotFoundException("credential not found");
        }
        keycloak.removeUserCredential(subject, id);
        return Response.noContent().build();
    }
}
