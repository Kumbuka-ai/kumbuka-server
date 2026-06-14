-- ===========================================================================
-- V11: the member's UI language preference.
--
-- Persists the console language choice server-side (a real user preference,
-- not just a cookie) so it follows the member across devices/sessions. NULL =
-- unset → the console falls back to a cookie / the browser / the default.
-- Additive, nullable; RLS unaffected (user_account already FORCE-RLS).
-- ===========================================================================

ALTER TABLE user_account ADD COLUMN locale VARCHAR(8);
