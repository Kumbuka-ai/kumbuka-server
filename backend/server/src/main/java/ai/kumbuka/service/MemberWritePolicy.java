package ai.kumbuka.service;

import ai.kumbuka.domain.UserAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;

/**
 * D-CORE-2 — per-member mute enforcement. A muted member keeps full read access
 * and full read/write of their PRIVATE scope, but SHARED-scope writes
 * (create/update/delete + shared forget) are suspended, on BOTH the console
 * admin API and the assistant's MCP channel.
 *
 * <p>This is the single place the rule lives; the two write surfaces call it.
 * It must run inside the tenant-bound transaction (the GUC is already set by the
 * {@code @TenantBound} interceptor), so the {@link UserAccount} lookup is
 * tenant-scoped. A member with no {@code user_account} row is treated as not
 * muted (the default) — rows are lazily created when an admin first mutes.
 */
@ApplicationScoped
public class MemberWritePolicy {

    /**
     * @throws MutedException if the caller is muted (shared writes suspended).
     *         Maps to HTTP 403 on the admin API and surfaces as a tool error on
     *         the MCP channel.
     */
    public void assertCanWriteShared(String subject) {
        UserAccount u = UserAccount.find("subject = ?1", subject).firstResult();
        if (u != null && Boolean.TRUE.equals(u.muted)) {
            throw new MutedException();
        }
    }

    public static class MutedException extends ForbiddenException {
        public MutedException() {
            super("Your account is muted: shared-scope writes are suspended. "
                + "Your private memory and all reads are unaffected. "
                + "Ask a team admin to lift the mute.");
        }
    }
}
