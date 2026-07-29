package ai.kumbuka.service;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.UserAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;

/**
 * The shared write-gate both surfaces (console admin API + assistant MCP
 * channel) call before a shared-scope mutation. It carries two orthogonal
 * checks:
 *
 * <ul>
 *   <li><b>Per-member mute</b> ({@link #assertCanWriteShared}) — a
 *       muted member keeps full read access and full read/write of their
 *       PRIVATE scope, but SHARED-scope writes (create/update/delete + shared
 *       forget) are suspended.</li>
 * <li><b>Scope content-lock</b>
 *       ({@link #assertScopeWritable}) — a {@code scope.locked} scope rejects
 *       every member mutation over EVERY surface; the one legitimate write path
 *       is a team-admin override on the console (audited by the caller).</li>
 * </ul>
 *
 * <p>This is the single place the rules live; the two write surfaces call it.
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

    /**
     * scope content-lock guard. Pre-check, called before any
     * create / update / delete / move-in / move-out on a shared scope:
     *
     * <ul>
     *   <li>{@code !scope.locked} → returns (the scope is open).</li>
     *   <li>{@code channel == MCP} → ALWAYS rejected on a locked scope, including
     *       for admins — the MCP wire is never an override surface (same rule as
     * the admin-lock on MCP).</li>
     *   <li>{@code channel == CONSOLE && !callerIsAdmin} → rejected (a member
     *       cannot mutate a locked scope on any surface).</li>
     *   <li>{@code channel == CONSOLE && callerIsAdmin} → returns — this is the
     *       one legitimate override path. The CALLER is responsible for emitting
     *       the {@code entry.override} governance-audit event.</li>
     * </ul>
     *
     * Read and copy-out (read + create into a non-locked scope) need no special
     * handling: a read never calls this, and a copy-out's create keys on its
     * (open) TARGET scope, which passes.
     *
     * @throws ScopeReadOnlyException when the locked scope rejects the write.
     */
    public void assertScopeWritable(Scope scope, SourceChannel channel, boolean callerIsAdmin) {
        if (!Boolean.TRUE.equals(scope.locked)) {
            return;
        }
        if (channel == SourceChannel.CONSOLE && callerIsAdmin) {
            return;   // admin console override — the caller audits it (Stage 2)
        }
        throw new ScopeReadOnlyException(scope.slug,
            "scope '" + scope.slug + "' is read-only (locked) — members cannot add, edit, "
            + "or delete entries; only a team admin may override, and only from the console.");
    }

    public static class MutedException extends ForbiddenException {
        public MutedException() {
            super("Your account is muted: shared-scope writes are suspended. "
                + "Your private memory and all reads are unaffected. "
                + "Ask a team admin to lift the mute.");
        }
    }
}
