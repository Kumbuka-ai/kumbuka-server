package ai.kumbuka.keycloak;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

/**
 * Tenant-scoping seam for Keycloak user operations.
 *
 * <p>Keycloak is a single realm. In the OSS single-tenant edition every realm
 * user IS the team, so the default implementation is a pass-through. In the
 * multi-tenant (SaaS) edition the realm is SHARED across tenants, so listing or
 * touching {@code realm().users()} unscoped would expose/allow management of
 * EVERY tenant's users. The SaaS edition supplies an implementation that scopes
 * every user operation to the caller's tenant by <strong>KC-Organization
 * membership</strong> (D-CORE-14) — the same axis the {@code organization} claim
 * is sourced from — closing that cross-tenant gap.
 *
 * <p>This is the user-directory analogue of the data-layer tenant isolation
 * (Hibernate {@code @TenantId} + RLS): the directory lives in Keycloak, outside
 * the database, so it needs its own scoping seam.
 *
 * <p>D-CORE-14 moved tenancy identity from the free {@code tenant_alias} user
 * attribute to KC-Organization membership; this seam moved with it. Membership
 * can only be bound <em>after</em> the user exists (it needs the user id), so the
 * create-time hook is {@link #enrolNewUser} (post-create), not a pre-create stamp.
 */
public interface TenantUserScope {

    /** The users visible to the caller. OSS: all realm users. SaaS: members of the caller's Org only. */
    List<UserRepresentation> listUsers(RealmResource realm);

    /**
     * Guard a user the caller is about to read or modify by id. OSS: no-op
     * (single tenant). SaaS: throws {@link jakarta.ws.rs.NotFoundException} when
     * the user is not a member of the caller's Organization — so a tenant admin
     * cannot read, role-change, disable, or inspect the sessions of another
     * tenant's user.
     */
    void assertVisible(RealmResource realm, UserRepresentation user);

    /**
     * Bind tenant ownership onto a just-created user (console invite path).
     * OSS: no-op. SaaS: adds the user as a member of the caller's KC-Organization
     * so the user is bound to the caller's tenant (and the membership-sourced
     * {@code organization} claim resolves) — not an orphaned, tenant-less row.
     * Called <em>after</em> the Keycloak user is created (membership needs the id).
     */
    void enrolNewUser(RealmResource realm, String keycloakUserId);
}
