-- ===========================================================================
-- V17 (FEAT-13): first_mcp_connected_at — the beta-activation funnel's
-- "first connection" step.
--
-- A write-once timestamp on the per-member tenant-membership row
-- (user_account): set ONCE, by the `mcp` adapter, on the member's first
-- authenticated MCP request (while still NULL); never touched thereafter. The
-- write-once nature is itself the structural guard against the column ever
-- becoming an activity log (constraint.audit-no-activity-monitoring): it carries
-- no frequency, no "last seen", no counter — only the first-connect instant.
--
-- Additive, nullable, NO backfill, NO default: a member who has never connected
-- over MCP stays NULL. Forward-only — the activation funnel of the beta cohort
-- is only measurable going forward, hence pre-beta now-or-never (FEAT-13).
-- V16-independent (touches only user_account, not the memory/ContentUnit
-- keystone). RLS unaffected (user_account is already FORCE-RLS, V3). Mirrors the
-- V11 locale / V15 onboarding additive shape.
-- ===========================================================================

ALTER TABLE user_account ADD COLUMN first_mcp_connected_at TIMESTAMPTZ;
