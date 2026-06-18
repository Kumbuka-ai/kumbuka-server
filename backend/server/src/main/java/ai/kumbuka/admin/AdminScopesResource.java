package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.CreateScopeRequest;
import ai.kumbuka.admin.dto.AdminDtos.ScopeView;
import ai.kumbuka.admin.dto.AdminDtos.UpdateScopeRequest;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.TeamSettings.CreateScopes;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import ai.kumbuka.repo.TeamSettingsRepository;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Scope CRUD over the admin REST API. Reads are member+admin; writes are
 * admin (with `members`-policy exception on POST per
 * {@link CreateScopes#MEMBERS}). Private rows are never exposed — listing
 * filters them out, addressing the private slug 404s.
 */
@TenantBound
@Transactional
@Path("/api/scopes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminScopesResource {

    @Inject ScopeRepository scopes;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject TeamSettingsRepository settings;
    @Inject SecurityIdentity identity;

    @GET
    @RolesAllowed({"admin", "member"})
    public List<ScopeView> list() {
        return scopes.listShared().stream()
            .map(s -> ScopeView.from(s, sharedMemories.listShared(s.slug, null).size()))
            .toList();
    }

    @POST
    @RolesAllowed({"admin", "member"})
    @Transactional
    public Response create(CreateScopeRequest req) {
        // Honour the team's createScopes policy at runtime (D3): when
        // ADMINS-only, a member-rolled caller is rejected here even though
        // @RolesAllowed lets them past the gate.
        boolean isAdmin = identity.getRoles().contains("admin");
        if (!isAdmin && settings.current().getCreateScopes() == CreateScopes.ADMINS) {
            throw new ForbiddenException("only admins may create scopes");
        }
        if (req.slug() == null || req.slug().isBlank() || req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("slug and name are required");
        }
        Scope s = scopes.createProject(
            req.slug().trim(),
            req.name().trim(),
            req.description() == null ? null : req.description().trim(),
            identity.getPrincipal().getName()
        );
        return Response.status(Response.Status.CREATED)
            .entity(ScopeView.from(s, 0L))
            .build();
    }

    @PATCH
    @Path("/{slug}")
    @RolesAllowed("admin")
    @Transactional
    public ScopeView rename(@PathParam("slug") String slug, UpdateScopeRequest req) {
        requireSharedSlug(slug);
        scopes.rename(slug, req.name(), req.description());
        Scope s = scopes.requireBySlug(slug);
        return ScopeView.from(s, sharedMemories.listShared(slug, null).size());
    }

    @POST
    @Path("/{slug}:archive")
    @RolesAllowed("admin")
    @Transactional
    public Response archive(@PathParam("slug") String slug) {
        requireSharedSlug(slug);
        scopes.archive(slug);
        return Response.noContent().build();
    }

    // dogfood-16: reverse of :archive (reversible soft-hide, no delete). Admin-only,
    // same guards (requireSharedSlug + the repo's fixed/non-project checks → 409
    // SCOPE_LOCKED). 204 on success — mirrors :archive and the console's void call.
    @POST
    @Path("/{slug}:unarchive")
    @RolesAllowed("admin")
    @Transactional
    public Response unarchive(@PathParam("slug") String slug) {
        requireSharedSlug(slug);
        scopes.unarchive(slug);
        return Response.noContent().build();
    }

    /** Reject /api/scopes/private addressing up-front — admin code paths
     *  have no business reaching the private scope (ADR-0003). */
    private void requireSharedSlug(String slug) {
        Scope s = scopes.requireBySlug(slug);
        if (s.kind == ScopeKind.PRIVATE) {
            throw new jakarta.ws.rs.NotFoundException("scope not found: " + slug);
        }
    }
}
