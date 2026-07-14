-- ===========================================================================
-- V18: per-user UI presentation settings — one typed field.
--
-- One jsonb column carries the console's presentation state (currently: the
-- overview connect block and the navigation sidebar, collapsed/expanded).
-- The shape is typed and validated at the API boundary (UiSettings): unknown
-- fields and wrong-typed fields are rejected with 400 — a new UI switch is a
-- DTO field, not a migration.
--
-- Presentation state ONLY may live here — never search terms, last-opened
-- anything, timestamps, counters, or history; nothing that lets anyone infer
-- how a user works. See the boundary note on the UiSettings type.
--
-- Additive, NOT NULL with an empty-object default — safe on a populated
-- table, no backfill needed; RLS unaffected (user_account is already
-- FORCE-RLS). Mirrors the V11-locale / V15-onboarding shape.
-- ===========================================================================

ALTER TABLE user_account ADD COLUMN settings JSONB NOT NULL DEFAULT '{}'::jsonb;
