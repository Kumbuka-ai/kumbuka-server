-- ===========================================================================
-- V6: scope_stats projection (shared-only) + kumbuka_ops_reader role.
--
-- ADR-0014.  This migration delivers two things the commercial ops-console
-- consumes:
--
--   1. A `scope_stats` table that holds per-(tenant, scope, type) counts
--      of MEMORIES IN SHARED SCOPES ONLY — `kind='private'` is excluded
--      by construction in the refresher's SQL (ScopeStatsRefresher.java).
--      The provider counts never come from `memory`; they come from this
--      table.
--
--   2. The `kumbuka_ops_reader` Postgres role with `SELECT` only on the
--      control-plane tables: `tenant` (via `team`), `scope` metadata,
--      `scope_stats`, `team_settings`, `user_account`.  It has NO grant
--      on `memory`.  A cross-tenant or buggy provider query against
--      `memory` therefore fails at the DB layer with `42501 permission
--      denied for table memory`.  P1 is a missing GRANT, not a missing
--      endpoint (ADR-0014 §P1).
--
-- Migration version: V6 (V5 is left reserved for the C2 follow-up —
-- shared-scope key uniqueness, ratified per implementation-answers §7).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. scope_stats — projection table, RLS-isolated like the rest.
-- ---------------------------------------------------------------------------
CREATE TABLE scope_stats (
    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL REFERENCES scope(id) ON DELETE CASCADE,
    scope_slug       TEXT         NOT NULL,
    -- 'private' deliberately not in the CHECK list — private rows are
    -- never projected here.  Defence-in-depth alongside the refresher's
    -- WHERE filter.
    scope_kind       VARCHAR(16)  NOT NULL CHECK (scope_kind IN ('project','global')),
    type             VARCHAR(32)  NOT NULL,
    entry_count      BIGINT       NOT NULL CHECK (entry_count >= 0),
    last_updated_at  TIMESTAMPTZ  NOT NULL,
    refreshed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, scope_id, type)
);

CREATE INDEX scope_stats_tenant_idx ON scope_stats (tenant_id);

-- Apply the same RLS posture as memory / scope / team_settings (ADR-0011).
-- Tenant isolation is structural; the table is just another datastore that
-- carries tenant_id and is filtered on the per-session GUC.
ALTER TABLE scope_stats ENABLE ROW LEVEL SECURITY;
ALTER TABLE scope_stats FORCE  ROW LEVEL SECURITY;

CREATE POLICY scope_stats_tenant_isolation ON scope_stats
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 2. kumbuka_ops_reader role — cross-tenant read on control-plane only.
--
-- BYPASSRLS is intentional and load-bearing: the provider operates
-- cross-tenant by design.  The structural P1 guarantee comes from the
-- GRANT shape that follows — this role physically cannot SELECT from
-- `memory` because it holds no privilege on the table.
--
-- The placeholder password is replaced via `ALTER ROLE … PASSWORD …` in
-- the production init script.  CI / DevServices Postgres ignores this
-- block when the role already exists.
-- ---------------------------------------------------------------------------
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_ops_reader') THEN
        CREATE ROLE kumbuka_ops_reader LOGIN BYPASSRLS
            PASSWORD 'change-me-kumbuka-ops-reader';
    END IF;
END
$do$;

GRANT USAGE ON SCHEMA public TO kumbuka_ops_reader;

-- The only count surface the provider gets.
GRANT SELECT ON scope_stats TO kumbuka_ops_reader;

-- Metadata-only access to scope (full row — there is no content in
-- scope; the description/name are not memory content).
GRANT SELECT (id, tenant_id, slug, name, kind, fixed, archived,
              description, created_by, created_at)
    ON scope TO kumbuka_ops_reader;

-- Identity metadata.  user_account mirrors Keycloak `sub` → email +
-- last_seen_at; not memory content.
GRANT SELECT ON user_account TO kumbuka_ops_reader;

-- Settings snapshot (write policy, default scope, create-scopes flag).
GRANT SELECT ON team_settings TO kumbuka_ops_reader;

-- Tenant directory needs name + created_at.
GRANT SELECT ON team TO kumbuka_ops_reader;

-- DELIBERATELY ABSENT: GRANT … ON memory TO kumbuka_ops_reader.
-- A bug, a malicious query, or a poorly reviewed feature in ops-console
-- that tries to read memory rows hits a Postgres permission error.
-- This is the structural guarantee called out in ADR-0014.
