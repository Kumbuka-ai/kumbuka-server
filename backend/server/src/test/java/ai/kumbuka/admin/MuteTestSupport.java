package ai.kumbuka.admin;

import ai.kumbuka.domain.UserAccount;
import ai.kumbuka.domain.UserStatus;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Test-only helper to seed a {@code user_account} row with a mute flag inside a
 * tenant-bound transaction (the row is RLS'd, so the {@code app.tenant_id} GUC
 * must be set — {@code @TenantBound} does that). Resolves the same default test
 * tenant the request resolves, so the seeded row is visible to the mute gate.
 *
 * <p>It sat in the {@code mcp} test package while the mute gate had two
 * surfaces to guard. One of them left with the memory engine; the helper moved
 * here, beside the one that remains.
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
