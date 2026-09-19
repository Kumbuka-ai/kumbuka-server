-- ===========================================================================
-- V23: the tenancy inventory leaves `public` and settles in `platform`.
--
-- V21 opened the schema `platform` and published ONE view into it. This
-- migration moves the base tables themselves, so that `platform` holds the
-- service's inventory and `public` stops being the place where a second
-- service's objects would collide with this one's.
--
-- WHAT MOVES, AND WHY EXACTLY THESE
--
-- Every table this chain owns EXCEPT the two memory tables and the schema
-- history. Measured against a fresh database at V22, `public` holds nine
-- relations; six of them move here:
--
--     governance_audit   scope   scope_stats   team   team_settings   user_account
--
--   * `memory` and `content_relation` stay. They are not going to `platform`
--     at all — they leave for the memory service's own schema in a later step,
--     and moving them here would only mean moving them twice. The same reason
--     keeps `content_relation_acyclic_supersedes()` where it is: it is that
--     step's object, not this one's.
--   * `flyway_schema_history` stays, because a migration CANNOT move it. Flyway
--     holds an AccessShareLock on it in a second connection for the whole run,
--     so `ALTER TABLE ... SET SCHEMA` here would wait on Flyway itself, and with
--     `lock_timeout = 0` it would wait forever — a container that never finishes
--     booting rather than a failure anyone sees. The history is relocated by the
--     upgrade script under `deploy/upgrade/`, which runs BEFORE this image, in
--     one transaction together with the migrator's search_path. Measured
--     against Flyway 12.0.0 before this was written.
--   * `pgcrypto` and its 36 routines are not touched. The extension is not this
--     stage's subject, and the tables that use its functions carry the
--     dependency by OID, so they keep resolving across the move.
--
-- EVERY STATEMENT IS SCHEMA-QUALIFIED
--
-- Deliberately, and not as a matter of taste. By the time an existing
-- installation runs this migration, the upgrade script has already set the
-- migrator's search_path to `platform, public` — so an unqualified
-- `ALTER TABLE scope` would resolve differently before and after that step. A
-- qualified name means this migration does the same thing on a fresh database
-- (search_path still `"$user", public`) and on an upgraded one.
--
-- WHAT THE MOVE CARRIES WITH IT, AND WHY NO DDL REPAIRS IT
--
-- Postgres holds these dependencies by OID, not by name, so they follow the
-- tables without a single further statement: the row-level security policies
-- of V3, the indexes, the triggers of V14, and the view `platform.scope_access`
-- of V21 — whose definition text simply re-prints as `platform.scope` and
-- `platform.user_account` afterwards. Measured before this was written: the view
-- returns byte-identical rows before and after, and the tenant filter still
-- binds. Do NOT add a CREATE OR REPLACE VIEW here; there is nothing to repair.
--
-- THE ONE GRANT, AND WHY IT READS THE CATALOGUE
--
-- V21 did `REVOKE ALL ON SCHEMA platform FROM PUBLIC`, so the owner of these
-- tables needs USAGE on `platform` to reach its own objects through a name.
-- Who that owner is differs by installation: on a fresh database it is the
-- migrator that just created them, while a deployment that runs an
-- owner-normalisation step has re-owned them to its runtime role. Naming a role
-- here would be wrong on one of the two, so the owner is read from the
-- catalogue instead. Granting USAGE to whoever already owns the objects widens
-- nothing.
--
-- WHAT IS DELIBERATELY NOT HERE
--
-- No EE table. `tenant_limits` and `import_credits` live in `public` too, but
-- this chain is the community one and must not know that they exist; they are
-- moved by the EE chain's own V10002. No role of the ops-console either: that
-- deployment's roles are its bootstrap's business, and this migration would be
-- naming a grantee a self-hoster does not have.
-- ===========================================================================

-- --- the six tables ---------------------------------------------------------
ALTER TABLE public.governance_audit SET SCHEMA platform;
ALTER TABLE public.scope            SET SCHEMA platform;
ALTER TABLE public.scope_stats      SET SCHEMA platform;
ALTER TABLE public.team             SET SCHEMA platform;
ALTER TABLE public.team_settings    SET SCHEMA platform;
ALTER TABLE public.user_account     SET SCHEMA platform;

-- --- the one routine that belongs to a moved table --------------------------
-- The append-only guard of V14. Its triggers sit on `governance_audit` and
-- reference it by OID, so trigger and function stay connected across the move.
-- Its body raises and reads nothing, so it needs no search_path of its own.
ALTER FUNCTION public.governance_audit_block_mutation() SET SCHEMA platform;

-- --- USAGE for whoever owns the moved tables --------------------------------
DO $do$
DECLARE
    inventory_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner)
      INTO inventory_owner
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'platform'
       AND c.relname = 'scope';

    IF inventory_owner IS NULL THEN
        RAISE EXCEPTION
            'V23: platform.scope is not present after the move — refusing to guess an owner'
            USING ERRCODE = 'P0001';
    END IF;

    EXECUTE format('GRANT USAGE ON SCHEMA platform TO %I', inventory_owner);
END
$do$;
