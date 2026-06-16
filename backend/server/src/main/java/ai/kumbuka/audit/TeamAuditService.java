package ai.kumbuka.audit;

import ai.kumbuka.domain.GovernanceAudit;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Map;

/**
 * Writes append-only governance-audit rows under the acting team-admin's KC
 * {@code sub} (D-OPS-16 / D-CORE-9 substrate). Tenant-scoped via
 * {@link TenantBound}; the row's payload carries outcome counts only — never
 * memory content.
 */
@ApplicationScoped
@TenantBound
public class TeamAuditService {

    @Transactional
    public void append(String actorSubject, String action, String targetSubject, Map<String, Object> payload) {
        GovernanceAudit row = new GovernanceAudit();
        row.actorSubject = actorSubject;
        row.action = action;
        row.targetSubject = targetSubject;
        row.payload = payload == null ? Map.of() : payload;
        row.persist();
    }
}
