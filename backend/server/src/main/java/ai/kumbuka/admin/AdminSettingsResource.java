package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.SettingsView;
import ai.kumbuka.admin.dto.AdminDtos.UpdateSettingsRequest;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.TeamSettings.CreateScopes;
import ai.kumbuka.domain.TeamSettings.WritePolicy;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.TeamSettingsRepository;
import ai.kumbuka.service.WritePolicyResolver;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@TenantBound
@Transactional
@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSettingsResource {

    @Inject TeamSettingsRepository settings;
    @Inject ScopeRepository scopes;
    @Inject WritePolicyResolver resolver;

    @GET
    @RolesAllowed({"admin", "member"})
    public SettingsView get() {
        return SettingsView.from(settings.current(), resolver.resolve());
    }

    @PATCH
    @RolesAllowed("admin")
    @Transactional
    public SettingsView update(UpdateSettingsRequest req) {
        TeamSettings current = settings.current();

        if (req.createScopes() != null) {
            current.setCreateScopes(CreateScopes.fromDb(req.createScopes()));
        }
        if (req.writePolicy() != null) {
            WritePolicy wp = WritePolicy.fromDb(req.writePolicy());
            UUID defaultScopeId = current.defaultScopeId;
            if (wp == WritePolicy.PROJECT) {
                if (req.defaultScopeSlug() == null) {
                    throw new BadRequestException(
                        "writePolicy=project requires a defaultScopeSlug");
                }
                Scope s = scopes.requireBySlug(req.defaultScopeSlug());
                if (s.archived || s.kind != ai.kumbuka.domain.ScopeKind.PROJECT) {
                    throw new BadRequestException(
                        "defaultScope must be an active project scope: "
                        + req.defaultScopeSlug());
                }
                defaultScopeId = s.id;
            } else {
                defaultScopeId = null;
            }
            current.setWritePolicy(wp);
            current.defaultScopeId = defaultScopeId;
        }
        return SettingsView.from(settings.current(), resolver.resolve());
    }
}
