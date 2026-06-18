-- ===========================================================================
-- V15: the tenant-owner's onboarding-wizard state (D-CORE-10.1).
--
-- The first-login onboarding wizard must stay dismissed across logins/devices.
-- Without a server field the console can only dismiss it for the browser
-- session, so it reopened on every login (finding dogfood-15a). Persist the
-- dismissed flag + the resume step server-side, keyed by the account row
-- (per-user, by KC sub — D-CORE-12).
--
-- `dismissed` = the "don't show again" checkbox OR completing the wizard (both
-- reach the same dismissed state, D-CORE-10.1). `last_step` = the resume point
-- (0-based) while still pending. Additive, NOT NULL with safe defaults
-- (fresh/existing rows = not-yet-dismissed, step 0); RLS unaffected
-- (user_account is already FORCE-RLS). Mirrors the V11 locale shape.
-- ===========================================================================

ALTER TABLE user_account ADD COLUMN onboarding_dismissed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_account ADD COLUMN onboarding_last_step SMALLINT NOT NULL DEFAULT 0;
