package ai.kumbuka.mcp;

import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.domain.UserStatus;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;

/**
 * FEAT-13 test helper. Seeds a {@code user_account} row and reads back its
 * {@code first_mcp_connected_at} inside a tenant-bound transaction (the row is
 * RLS'd, so the {@code app.tenant_id} GUC must be set — {@code @TenantBound}
 * does that), resolving the same default test tenant the tools resolve.
 */
@ApplicationScoped
@TenantBound
public class FirstMcpConnectTestSupport {

    /** Provision a fresh member — {@code first_mcp_connected_at} starts null. */
    @Transactional
    public void seedMember(String subject) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (u == null) {
            u = new UserAccount();
            u.subject = subject;
            u.email = subject + "@example.com";
            u.role = "member";
            u.status = UserStatus.ACTIVE;
            u.persist();
        }
        u.firstMcpConnectedAt = null;
    }

    /** The member's first-connect stamp, or null if never connected over MCP. */
    @Transactional
    public Instant firstConnectedAt(String subject) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        return u == null ? null : u.firstMcpConnectedAt;
    }
}
