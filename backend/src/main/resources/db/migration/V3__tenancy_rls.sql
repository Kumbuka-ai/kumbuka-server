-- ===========================================================================
-- V3: Row-Level Security on the tenant axis (see ADR-0011).
--
-- This is Layer 2 of the two-layer enforcement model.  Layer 1 is the
-- Hibernate @TenantId annotation, which filters ORM-routed reads/writes.
-- Layer 2 catches the rest: raw SQL, native queries, code paths that bypass
-- the ORM, or queries an engineer hand-rolled without remembering the
-- tenant predicate.  Either layer alone would be load-bearing; both make
-- this the structural seam ADR-0011 ratified.
--
-- The policies key on a per-session GUC `app.tenant_id`.  The application
-- sets it at every transaction start (see TenantDatabaseBinding).  When the
-- GUC is unset, `NULLIF(current_setting('app.tenant_id', true), '')` returns NULL and
-- `<col> = NULL::uuid` is FALSE — RLS fails closed.  That is intended.
--
-- This migration is pure DDL.  No INSERT/UPDATE/DELETE runs here, so no
-- tenant context is required to apply it (RLS only filters DML).  A
-- Flyway `beforeEachMigrate` callback (TenantyMigrationCallback.java) sets
-- the GUC to the singleton tenant for any future migration that does
-- carry DML.
-- ===========================================================================

-- --- memory --------------------------------------------------------------
ALTER TABLE memory ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory FORCE  ROW LEVEL SECURITY;
CREATE POLICY memory_tenant_isolation ON memory
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- scope ---------------------------------------------------------------
ALTER TABLE scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE scope FORCE  ROW LEVEL SECURITY;
CREATE POLICY scope_tenant_isolation ON scope
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- user_account --------------------------------------------------------
ALTER TABLE user_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_account FORCE  ROW LEVEL SECURITY;
CREATE POLICY user_account_tenant_isolation ON user_account
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- team_settings -------------------------------------------------------
ALTER TABLE team_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_settings FORCE  ROW LEVEL SECURITY;
CREATE POLICY team_settings_tenant_isolation ON team_settings
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- team ----------------------------------------------------------------
ALTER TABLE team ENABLE ROW LEVEL SECURITY;
ALTER TABLE team FORCE  ROW LEVEL SECURITY;
CREATE POLICY team_tenant_isolation ON team
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
