package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.MemberSummary;
import ai.kumbuka.admin.dto.AdminDtos.OverviewView;
import ai.kumbuka.admin.dto.AdminDtos.RecentActivity;
import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.repo.ScopeRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard aggregate. Counts, recent shared activity, member summary,
 * type distribution. Never touches private rows — type distribution + recent
 * activity come from {@link SharedMemoryRepository}.
 */
@TenantBound
@Transactional
@Path("/api/overview")
@Produces(MediaType.APPLICATION_JSON)
public class AdminOverviewResource {

    private static final int RECENT_LIMIT = 10;

    @Inject ScopeRepository scopes;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject MemoryConfig config;

    @GET
    @RolesAllowed({"admin", "member"})
    public OverviewView get(@Context SecurityContext security) {
        var allScopes = scopes.listAll();
        long scopesTotal = allScopes.stream()
            .filter(s -> s.kind != ScopeKind.PRIVATE)
            .count();
        long scopesArchived = scopes.find(
            "archived = true and kind != ?1", ScopeKind.PRIVATE
        ).count();

        List<Memory> allShared = sharedMemories.listShared(null, null);
        long entriesTotal = allShared.size();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (MemoryType t : MemoryType.values()) {
            byType.put(t.dbValue(), allShared.stream()
                .filter(m -> m.type == t)
                .count());
        }

        List<RecentActivity> recent = allShared.stream()
            .sorted((a, b) -> b.updatedAt.compareTo(a.updatedAt))
            .limit(RECENT_LIMIT)
            .map(RecentActivity::from)
            .toList();

        // Member summary is ADMIN ONLY. It carries roster PII such as email
        // and role, so it must not reach a plain member -- the same P0 read
        // leak as the admin users list. A member receives an empty list and
        // resolves author display names through the members directory instead.
        List<MemberSummary> members = security.isUserInRole("admin")
            ? UserAccount.<UserAccount>list("order by email").stream()
                .map(u -> new MemberSummary(
                    u.id, u.subject, u.email, u.displayName,
                    u.role, u.status.dbValue(), Boolean.TRUE.equals(u.muted)))
                .toList()
            : List.of();

        return new OverviewView(scopesTotal, scopesArchived, entriesTotal, byType, recent, members);
    }
}
