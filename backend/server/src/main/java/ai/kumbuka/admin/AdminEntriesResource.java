package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.CreateEntryRequest;
import ai.kumbuka.admin.dto.AdminDtos.EntryView;
import ai.kumbuka.admin.dto.AdminDtos.UpdateEntryRequest;
import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import ai.kumbuka.util.ReferenceUrlValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Memory entries inside a (shared) scope. Reads are member+admin; writes
 * are admin. Private scope addressing → 404.
 */
@TenantBound
@Transactional
@Path("/api/scopes/{slug}/entries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminEntriesResource {

    @Inject ScopeRepository scopes;
    @Inject MemoryRepository memories;           // write path (sets source=CONSOLE)
    @Inject SharedMemoryRepository sharedMemories;
    @Inject MemoryConfig config;
    @Inject SecurityIdentity identity;

    @GET
    @RolesAllowed({"admin", "member"})
    public List<EntryView> list(@PathParam("slug") String slug) {
        requireSharedSlug(slug);
        return sharedMemories.listShared(slug, null).stream()
            .map(EntryView::from)
            .toList();
    }

    @POST
    @RolesAllowed("admin")
    @Transactional
    public Response create(@PathParam("slug") String slug, CreateEntryRequest req) {
        requireSharedSlug(slug);
        ReferenceUrlValidator.validate(req.reference());
        MemoryType t = MemoryType.fromDb(req.type());
        Memory m = memories.remember(
            identity.getPrincipal().getName(),
            slug,
            t,
            req.key(),
            req.content(),
            SourceChannel.CONSOLE
        );
        if (req.reference() != null && !req.reference().isBlank()) {
            m.reference = req.reference();   // D-CORE-7: explicit admin write
        }
        return Response.status(Response.Status.CREATED)
            .entity(EntryView.from(m))
            .build();
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("admin")
    @Transactional
    public EntryView update(@PathParam("slug") String slug,
                            @PathParam("id") UUID id,
                            UpdateEntryRequest req) {
        requireSharedSlug(slug);
        ReferenceUrlValidator.validate(req.reference());
        MemoryType t = req.type() == null ? null : MemoryType.fromDb(req.type());
        Memory m = sharedMemories.update(id, req.content(), t);
        if (req.reference() != null) {   // D-CORE-7: null preserves, blank clears
            m.reference = req.reference().isBlank() ? null : req.reference();
        }
        // Guard: the updated row must still be in the requested scope.
        if (!m.scope.slug.equals(slug)) {
            throw new NotFoundException("entry not in scope " + slug);
        }
        return EntryView.from(m);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("admin")
    @Transactional
    public Response delete(@PathParam("slug") String slug, @PathParam("id") UUID id) {
        requireSharedSlug(slug);
        // Lookup first so we 404 cleanly when the id is wrong; deleteShared
        // returns 0 silently otherwise.
        Memory m = sharedMemories.findSharedById(id);
        if (m == null || !m.scope.slug.equals(slug)) {
            throw new NotFoundException("entry not found in scope " + slug);
        }
        sharedMemories.deleteShared(id);
        return Response.noContent().build();
    }

    private void requireSharedSlug(String slug) {
        Scope s = scopes.requireBySlug(slug);
        if (s.kind == ScopeKind.PRIVATE) {
            throw new NotFoundException("scope not found: " + slug);
        }
    }
}
