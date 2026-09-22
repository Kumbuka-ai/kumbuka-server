package ai.kumbuka.admin;
import ai.kumbuka.tenancy.TenantBound;

import ai.kumbuka.admin.dto.AdminDtos.MemberSummary;
import ai.kumbuka.admin.dto.AdminDtos.OverviewView;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.repo.ScopeRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;

/**
 * Dashboard aggregate. Scope counts and the member summary.
 *
 * <p>The entry total, the per-type distribution and the recent-activity feed
 * were built from the entry tables and left with the memory engine. They are
 * <em>absent</em> from the response rather than reported as {@code 0} or an
 * empty list: a zero would assert that the team has no entries, which this
 * service can no longer know; leaving the field out says only that it does not
 * speak for that number, which is true.
 */
@TenantBound
@Transactional
@Path("/api/overview")
@Produces(MediaType.APPLICATION_JSON)
public class AdminOverviewResource {

    @Inject ScopeRepository scopes;

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

        return new OverviewView(scopesTotal, scopesArchived, members);
    }
}
