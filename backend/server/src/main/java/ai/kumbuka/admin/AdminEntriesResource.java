package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.CreateEntryRequest;
import ai.kumbuka.admin.dto.AdminDtos.EntryView;
import ai.kumbuka.admin.dto.AdminDtos.RemapEntryRequest;
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
import ai.kumbuka.service.MemberWritePolicy;
import ai.kumbuka.util.MemoryContentValidator;
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
 * Memory entries inside a (shared) scope. Reads and writes are member+admin:
 * the role gate lets members past, then {@link MemberWritePolicy} decides at
 * runtime (a muted member is rejected, a normal member writes) — the same
 * service the MCP write tools use, so the console and the assistant gate
 * shared writes identically (D-CORE-2). This mirrors the structural-enforcement
 * pattern in {@link AdminScopesResource}. Private scope addressing → 404.
 *
 * <p>The runtime gate is also where the future D-CORE-13 admin-lock check will
 * sit (admin-locked entries reject member writes); it is intentionally not
 * built here.
 */
@TenantBound
@Transactional
@Path("/api/scopes/{slug}/entries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminEntriesResource {

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_MEMBER = "member";

    @Inject ScopeRepository scopes;
    @Inject MemoryRepository memories;           // write path (sets source=CONSOLE)
    @Inject SharedMemoryRepository sharedMemories;
    @Inject MemoryConfig config;
    @Inject SecurityIdentity identity;
    @Inject MemberWritePolicy writePolicy;
    @Inject ai.kumbuka.audit.TeamAuditService audit;   // D-CORE-17 remap governance event

    @GET
    @RolesAllowed({ROLE_ADMIN, ROLE_MEMBER})
    public List<EntryView> list(@PathParam("slug") String slug) {
        requireSharedSlug(slug);
        return sharedMemories.listShared(slug, null).stream()
            .map(EntryView::from)
            .toList();
    }

    @POST
    @RolesAllowed({ROLE_ADMIN, ROLE_MEMBER})
    @Transactional
    public Response create(@PathParam("slug") String slug, CreateEntryRequest req) {
        Scope scope = requireSharedSlug(slug);
        // Runtime write-gate (D-CORE-2): muted members are rejected here, normal
        // members pass. This is also the seam for the D-CORE-13 lock check.
        writePolicy.assertCanWriteShared(identity.getPrincipal().getName());
        // FEAT-19 / D-CORE-18: a locked scope rejects member writes; a team-admin
        // overrides (audited after the write). isAdmin is the runtime role, the
        // same source the createScopes policy reads.
        boolean isAdmin = callerIsAdmin();
        writePolicy.assertScopeWritable(scope, SourceChannel.CONSOLE, isAdmin);
        MemoryContentValidator.validate(req.content());   // F-1: ≤1500, server-side
        ai.kumbuka.util.MemoryKeyValidator.validate(req.key());   // E2E-06: key format
        ReferenceUrlValidator.validate(req.reference());
        MemoryType t = MemoryType.fromDb(req.type());
        // D-CORE-16: the console "new entry" path must NOT silently upsert by key
        // (dogfood-21). createShared rejects an existing key (author-independent)
        // with KEY_EXISTS → 409; the MCP memory_remember upsert is untouched.
        Memory m = memories.createShared(
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
        auditOverrideIfLocked(scope, m.logicalId, "create");
        return Response.status(Response.Status.CREATED)
            .entity(EntryView.from(m))
            .build();
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed({ROLE_ADMIN, ROLE_MEMBER})
    @Transactional
    public EntryView update(@PathParam("slug") String slug,
                            @PathParam("id") UUID id,
                            UpdateEntryRequest req) {
        Scope scope = requireSharedSlug(slug);
        writePolicy.assertCanWriteShared(identity.getPrincipal().getName());   // D-CORE-2 (see create)
        // FEAT-19 / D-CORE-18: locked scope rejects member edits; admin overrides.
        boolean isAdmin = callerIsAdmin();
        writePolicy.assertScopeWritable(scope, SourceChannel.CONSOLE, isAdmin);
        MemoryContentValidator.validate(req.content());   // F-1: ≤1500, server-side
        ReferenceUrlValidator.validate(req.reference());
        MemoryType t = req.type() == null ? null : MemoryType.fromDb(req.type());
        // Amendment 4: the acting admin is stamped as updated_by on the edit.
        // A still-SYSTEM-locked row (D-CORE-11) throws inside update() here — a
        // separate, still-binding axis — so the override audit below never fires
        // for a row the entry-level lock rejected (axis composition, D-CORE-18).
        Memory m = sharedMemories.update(id, req.content(), t, identity.getPrincipal().getName());
        if (req.reference() != null) {   // D-CORE-7: null preserves, blank clears
            m.reference = req.reference().isBlank() ? null : req.reference();
        }
        // Guard: the updated row must still be in the requested scope.
        if (!m.scope.slug.equals(slug)) {
            throw new NotFoundException("entry not in scope " + slug);
        }
        auditOverrideIfLocked(scope, m.logicalId, "update");
        return EntryView.from(m);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({ROLE_ADMIN, ROLE_MEMBER})
    @Transactional
    public Response delete(@PathParam("slug") String slug, @PathParam("id") UUID id) {
        Scope scope = requireSharedSlug(slug);
        writePolicy.assertCanWriteShared(identity.getPrincipal().getName());   // D-CORE-2 (see create)
        // FEAT-19 / D-CORE-18: locked scope rejects member deletes; admin overrides.
        boolean isAdmin = callerIsAdmin();
        writePolicy.assertScopeWritable(scope, SourceChannel.CONSOLE, isAdmin);
        // Lookup first so we 404 cleanly when the id addresses no row; deleteShared
        // returns 0 silently otherwise. A synthetic id for a built-in guidance entry
        // resolves to nothing here: the built-in entries are a read layer, not table
        // rows, so this row-addressed route answers "not found" — never a typed
        // reserved-namespace conflict, even though such an entry's key is reserved.
        // That typed rejection belongs where a real row is addressed; a real row that
        // carries a reserved key (only reachable below the write seam) is loaded here
        // and refused inside deleteShared.
        Memory m = sharedMemories.findSharedById(id);
        if (m == null || !m.scope.slug.equals(slug)) {
            throw new NotFoundException("entry not found in scope " + slug);
        }
        // A locked row (D-CORE-11) is rejected by the lock check inside
        // deleteShared — the still-binding entry axis — before the override audit
        // (there is no delete trigger below it since V20).
        sharedMemories.deleteShared(id);
        auditOverrideIfLocked(scope, m.logicalId, "delete");
        return Response.noContent().build();
    }

    // D-CORE-17: a team-admin re-homes a shared entry into another shared scope
    // (either direction). Atomic scope_id move (lossless); private excluded
    // structurally; target key-collision reuses D-CORE-16 (KEY_EXISTS, with an
    // optional key override to rename). Recorded as a governance-audit event
    // (scopes/ids only, never content). MCP never reaches this — UI-only (D-CORE-13).
    @POST
    @Path("/{id}:remap")
    @RolesAllowed(ROLE_ADMIN)
    @Transactional
    public EntryView remap(@PathParam("slug") String slug,
                           @PathParam("id") UUID id,
                           RemapEntryRequest req) {
        Scope source = requireSharedSlug(slug);   // source must be shared (private → 404)
        if (req == null || req.targetScope() == null || req.targetScope().isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("targetScope is required");
        }
        Memory m = sharedMemories.findSharedById(id);   // null for unknown OR private
        if (m == null || !m.scope.slug.equals(slug)) {
            throw new NotFoundException("entry not found in scope " + slug);
        }
        String fromScope = m.scope.slug;
        // FEAT-19 / D-CORE-18: remap is admin-only (@RolesAllowed(ROLE_ADMIN)).
        // Move-out mutates the SOURCE, move-in mutates the TARGET — gate BOTH.
        // Resolve the target up-front (memories.remap re-resolves it; a missing
        // target routes to SCOPE_NOT_FOUND). Since only admins reach remap, a
        // locked side is inherently an override → marked in the audit below.
        boolean isAdmin = callerIsAdmin();
        Scope target = scopes.requireBySlug(req.targetScope().trim());
        writePolicy.assertScopeWritable(source, SourceChannel.CONSOLE, isAdmin);
        writePolicy.assertScopeWritable(target, SourceChannel.CONSOLE, isAdmin);
        boolean override = Boolean.TRUE.equals(source.locked) || Boolean.TRUE.equals(target.locked);
        Memory moved = memories.remap(m, req.targetScope().trim(), req.key());
        audit.append(
            identity.getPrincipal().getName(),
            "scope.remap",
            null,
            java.util.Map.of(
                "entryId", id.toString(),
                "key", moved.key == null ? "" : moved.key,
                "fromScope", fromScope,
                "toScope", moved.scope.slug,
                "override", override));
        return EntryView.from(moved);
    }

    /** Runtime role of the caller — the same source the createScopes policy and
     *  the FEAT-19 scope-lock override read. */
    private boolean callerIsAdmin() {
        return identity.getRoles().contains(ROLE_ADMIN);
    }

    private Scope requireSharedSlug(String slug) {
        Scope s = scopes.requireBySlug(slug);
        if (s.kind == ScopeKind.PRIVATE) {
            throw new NotFoundException("scope not found: " + slug);
        }
        return s;
    }

    /**
     * FEAT-19 / D-CORE-18: when an admin override write lands on a content-locked
     * scope, emit a content-free {@code entry.override} governance-audit event
     * (actor, scope, entryId, operation — never memory content). A no-op on an
     * open scope. Only admins reach this on a locked scope — the
     * {@link MemberWritePolicy#assertScopeWritable} guard rejects members first,
     * and the SYSTEM entry-lock (D-CORE-11) throws before the call site on a
     * still-protected row, so a blocked write emits nothing.
     */
    private void auditOverrideIfLocked(Scope scope, UUID entryId, String operation) {
        if (Boolean.TRUE.equals(scope.locked)) {
            audit.append(
                identity.getPrincipal().getName(),
                "entry.override",
                null,
                java.util.Map.of(
                    "scope", scope.slug,
                    "entryId", entryId.toString(),
                    "operation", operation));
        }
    }
}
