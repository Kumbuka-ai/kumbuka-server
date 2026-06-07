-- ===========================================================================
-- V4: surrogate PK on team_settings so tenant_id is a plain @TenantId column.
--
-- The original schema used team_settings.tenant_id as the primary key
-- (one row per tenant; tenant_id == id). Hibernate 6 DISCRIMINATOR
-- multi-tenancy auto-populates the @TenantId column at persist time, so
-- having @TenantId sit on @Id forks the column's lifecycle between the
-- ORM's tenant binding and the application's id assignment. ADR-0011 §M4
-- handles this by promoting tenant_id to a plain column and giving
-- team_settings a UUID surrogate PK with a UNIQUE on tenant_id (still
-- one row per tenant).
--
-- This migration contains DML; the Flyway beforeEachMigrate callback
-- (TenantyMigrationCallback) sets app.tenant_id to the singleton so
-- RLS WITH CHECK lets the backfill UPDATE land.
-- ===========================================================================

ALTER TABLE team_settings ADD COLUMN id UUID DEFAULT gen_random_uuid();

UPDATE team_settings SET id = gen_random_uuid() WHERE id IS NULL;

ALTER TABLE team_settings ALTER COLUMN id SET NOT NULL;
ALTER TABLE team_settings ALTER COLUMN id SET DEFAULT gen_random_uuid();

ALTER TABLE team_settings DROP CONSTRAINT team_settings_pkey;
ALTER TABLE team_settings ADD PRIMARY KEY (id);

-- Still one row per tenant.
ALTER TABLE team_settings ADD CONSTRAINT uq_team_settings_tenant UNIQUE (tenant_id);
