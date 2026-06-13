-- ===========================================================================
-- V9: per-member mute write-state (D-CORE-2).
--
-- `muted` is a reversible, admin-set flag on a member: shared-scope writes
-- (create/update/delete + shared forget, on the console AND the assistant's MCP
-- channel) are suspended while it is true. The member keeps full read access and
-- full read/write/forget of their PRIVATE scope. It is NOT a role and NOT
-- disabled — role and status are unchanged.
--
-- Authorization lives in kumbuka-server (Keycloak is for authentication only),
-- so the flag is a DB column here, not a Keycloak attribute: it takes effect
-- immediately (no token-refresh lag) and adds no realm config. Additive +
-- non-null with a safe default; `user_account` is already tenant-scoped + RLS'd,
-- so isolation is unaffected.
-- ===========================================================================

ALTER TABLE user_account ADD COLUMN muted BOOLEAN NOT NULL DEFAULT FALSE;
