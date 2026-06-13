package ai.kumbuka.mcp;

import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.domain.UserStatus;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Test-only helper to seed a {@code user_account} row with a mute flag inside a
 * tenant-bound transaction (the row is RLS'd, so the {@code app.tenant_id} GUC
 * must be set — {@code @TenantBound} does that). Resolves the same default test
 * tenant the tools resolve, so the seeded row is visible to the mute gate.
 */
@ApplicationScoped
@TenantBound
public class MuteTestSupport {

    @Transactional
    public void setMuted(String subject, boolean muted) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (u == null) {
            u = new UserAccount();
            u.subject = subject;
            u.email = subject + "@example.com";
            u.role = "member";
            u.status = UserStatus.ACTIVE;
            u.persist();
        }
        u.muted = muted;
    }
}
