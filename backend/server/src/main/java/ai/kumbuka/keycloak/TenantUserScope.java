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
 * every user operation to the caller's tenant (by the {@code tenant_alias} user
 * attribute), closing that cross-tenant gap.
 *
 * <p>This is the user-directory analogue of the data-layer tenant isolation
 * (Hibernate {@code @TenantId} + RLS): the directory lives in Keycloak, outside
 * the database, so it needs its own scoping seam.
 */
public interface TenantUserScope {

    /** The users visible to the caller. OSS: all realm users. SaaS: the caller's tenant only. */
    List<UserRepresentation> listUsers(RealmResource realm);

    /**
     * Guard a user the caller is about to read or modify by id. OSS: no-op
     * (single tenant). SaaS: throws {@link jakarta.ws.rs.NotFoundException} when
     * the user does not belong to the caller's tenant — so a tenant admin cannot
     * read, role-change, disable, or inspect the sessions of another tenant's user.
     */
    void assertVisible(UserRepresentation user);

    /**
     * Stamp tenant ownership onto a user being created (console invite path).
     * OSS: no-op. SaaS: sets the {@code tenant_alias} attribute so the new user
     * belongs to the caller's tenant (and is not an orphaned, tenant-less row).
     */
    void stampNewUser(UserRepresentation user);
}
