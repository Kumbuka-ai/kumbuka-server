-- ===========================================================================
-- V24: the platform read contract grows up — one alias resolution and one
--      scope answer, shaped the same for every service that asks.
--
-- V21 published `platform.scope_access` for two consumers that only needed to
-- know whether a shared project scope existed. Three services now sit behind
-- the same contract, and measured against a database at V23 it does not carry
-- them:
--
--   * the view answers for `kind = 'project'` only, so a service cannot address
--     a private or a global scope at all, and it publishes neither the scope's
--     kind nor its content lock — so a service cannot tell a retired scope from
--     a frozen one, nor refuse a write for the reason the core would.
--   * alias resolution is not in the contract at all. It lives in
--     `public.team_tenant_id_by_alias`, created by the ops-console bootstrap,
--     owned by a BYPASSRLS provider role, with EXECUTE held by the core alone.
--     A service migrator cannot grant itself EXECUTE on another role's
--     function, so every service that needs a tenant id from an alias is stuck.
--
-- This migration closes both, once, for all of them. It builds nothing in any
-- service and nothing in the core's own code.
--
-- ---------------------------------------------------------------------------
-- WHY THE ALIAS LOOKUP NEEDS A ROLE OF ITS OWN
-- ---------------------------------------------------------------------------
--
-- `platform.team` is under FORCE ROW LEVEL SECURITY, and its policy keys on
-- `app.tenant_id`. An alias lookup runs BEFORE any tenant binding exists — that
-- is its whole purpose (D-OPS-26: the tenant id never comes from the token,
-- only the alias). So the lookup must see `team` past row-level security, and
-- SECURITY DEFINER alone does not achieve that: a definer function reads with
-- its OWNER's privileges, and every owner this chain can name is bound by the
-- same policy.
--
-- Measured against a database at V23, as the service role, with no tenant bound
-- (2026-09-20):
--
--   owner = kumbuka (the base-table owner, FORCE RLS binds it)
--       SELECT platform.tenant_id_by_alias('alpha')  ->  NULL
--   the same body with `SET row_security = off`
--       ERROR: query would be affected by row-level security policy for
--              table "team"
--   owner = a BYPASSRLS role
--       SELECT platform.tenant_id_by_alias('alpha')  ->  the tenant id
--
-- The first of those three is the dangerous one: a known alias and an unknown
-- alias both answer NULL, nothing raises, and every caller reports the tenant
-- as unknown. A contract that fails that way is worse than one that is absent.
--
-- The chain cannot hand the function to a BYPASSRLS role. Only a superuser may
-- grant BYPASSRLS, the migrator is deliberately not one (stage F), and pinning
-- ownership inside a migration was rejected outright for the view (ADR-0026
-- variant 2a; V21). So this migration does the narrow thing instead:
--
--   * a role `kumbuka_alias_resolver` — NOLOGIN, NOINHERIT, NOT BYPASSRLS,
--     nobody's member, which nothing can connect as and no service role may
--     SET ROLE to;
--   * a column-level SELECT on exactly `(tenant_id, alias)` of `platform.team`
--     — not the table, and not the columns that carry a tenant's name;
--   * ONE policy on `platform.team`, `TO kumbuka_alias_resolver`, which is
--     therefore unreachable for every other role in the cluster. A policy
--     addressed to a role is not a hole a GUC can be steered into; the marker
--     variant that would have been (`current_setting('app.alias_lookup')`) was
--     measured working and rejected for exactly that reason — it would have
--     opened `team` to anything holding SELECT on it.
--
-- Measured after building it, as each service role: a known alias resolves, an
-- unknown one answers NULL, and `SELECT count(*) FROM platform.team` still
-- answers 0 for the core and `permission denied` for every service role. The
-- resolver widens the alias lookup and nothing else.
--
-- ---------------------------------------------------------------------------
-- WHAT `can_write` MEANS, AND WHAT IT CANNOT MEAN
-- ---------------------------------------------------------------------------
--
-- Read out of `MemberWritePolicy` as the specification, not out of this view:
--
--   assertCanWriteShared(subject) — a muted member loses SHARED writes. It is
--       called only when the scope is not the private one, so `muted` does not
--       touch a member's own private scope.
--   assertScopeWritable(scope, channel, callerIsAdmin) — a locked scope refuses
--       every write, EXCEPT from a team admin on the console.
--
-- The exception is the part this view cannot carry. The override keys on the
-- CHANNEL — `SourceChannel.CONSOLE` with an admin principal — and a channel is
-- not a row in `platform`. Every MCP call site passes `false` for the admin
-- flag unconditionally, so over any wire that is not the console the override
-- does not exist. `can_write` is therefore the write right of the calling
-- subject OVER A SERVICE CHANNEL, and a console admin's override stays what it
-- is today: a decision the console makes, from the role in its own token, with
-- `locked` (published here) as its input. This is reported as a finding rather
-- than papered over: a column that claimed to answer for the console too would
-- be wrong exactly where a governance audit event is required.
--
-- ---------------------------------------------------------------------------
-- WHAT A PRIVATE SCOPE'S VISIBILITY RESTS ON — MEASURED, NOT ASSUMED
-- ---------------------------------------------------------------------------
--
-- `platform.scope` has no author column for a private scope, and it holds at
-- most ONE private scope per tenant: `uq_scope_one_private` (V1) is a unique
-- index on `tenant_id` where `kind = 'private'`, and a second insert is
-- refused. `created_by` (V2) is documented and seeded as NULL for system seeds,
-- and it is NULL on every private and global scope a fresh chain produces.
--
-- So "a private scope belongs to its author" is not a row that exists today.
-- Privacy sits ONE LEVEL DOWN, on `memory.owner_subject`: the private scope is
-- a per-tenant container and the core filters the rows inside it by owner.
--
-- The author check below is therefore written so that it BINDS WHERE AN AUTHOR
-- EXISTS and does not blank out the scope that has none:
--
--     s.kind <> 'private' OR s.created_by IS NULL OR s.created_by = <subject>
--
-- An authored private scope is visible to its author alone. The one autorless
-- per-tenant private scope stays visible to every active member — which is not
-- a widening: it is what the core does today, and making it invisible instead
-- would publish a contract that answers nothing for the one private scope that
-- actually exists.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. the dispatch service's role carries the service's name.
--
-- `kumbuka_logbook` (V21) was named after a service that has since been renamed;
-- a later log:// service must not inherit the confusion. A RENAME is the right
-- shape because privileges hang off the role's OID and follow it: measured on a
-- database at V23, the SELECT on `platform.scope_access` was still held under
-- the new name immediately after the rename, and the SCRAM verifier survived it.
--
-- AN MD5 VERIFIER DOES NOT SURVIVE IT, AND THAT IS WHAT THE GUARD BELOW IS FOR.
-- It is salted with the ROLE NAME, so a rename cannot carry it across; Postgres
-- drops it and says so in passing. Measured on postgres:16.13, 2026-09-20:
--
--     ALTER ROLE kumbuka_logbook RENAME TO kumbuka_dispatch
--     NOTICE:  MD5 password cleared because of role rename
--     rolpassword afterwards  ->  NULL
--
-- A NOTICE in a migration log is not a gate. What it leaves behind is a service
-- role that exists, holds every privilege it used to hold, and can no longer
-- authenticate — and the deployment reports a clean migration. That this chain's
-- own roles are SCRAM was measured in a TEST substrate, which says nothing about
-- a cluster whose `password_encryption` was `md5` when the password was last
-- set. A measurement taken somewhere else is not a guard, so the check is here.
--
-- THE CHECK NEEDS TO READ `pg_catalog.pg_authid`, AND NOT EVERY MIGRATOR MAY.
-- Measured the same day, as a CREATEROLE non-superuser (the migrator stage F
-- intends):
--
--     SELECT rolpassword FROM pg_authid   ->  ERROR: permission denied for table pg_authid
--     SELECT passwd FROM pg_shadow        ->  ERROR: permission denied for view pg_shadow
--     SELECT rolpassword FROM pg_roles    ->  '********'
--
-- The third is the one that rules out a workaround: `pg_roles.rolpassword` is a
-- CONSTANT in the view definition, and it reads '********' for a role with no
-- password at all — so it cannot even witness that a verifier EXISTS, let alone
-- which kind it is. Nor can the migrator borrow the read the way the resolver
-- block below borrows CREATE: `GRANT pg_read_all_data TO current_user` is
-- refused ("Only roles with the ADMIN option on role pg_read_all_data may grant
-- this role"), because a predefined role's ADMIN option is the superuser's.
--
-- So a migrator that cannot read the verifier is refused the rename rather than
-- performing it blind. The deployment is unaffected: it migrates the core as
-- the superuser (`QUARKUS_FLYWAY_USERNAME` is set to the POSTGRES_USER of the
-- cluster in `infra/compose.prod.yml`, where the comment states the reason —
-- the RLS backfills in V7 need it). The variable is named without its shell
-- braces on purpose: Flyway reads a dollar sign followed by a braced name as a
-- PLACEHOLDER everywhere in the file, comments included, and then refuses to
-- parse the migration at all — "No value provided for placeholder" — so the
-- whole chain stops on a sentence in a comment. Measured here first, twice. A stage-F migrator needs one grant to pass this
-- check, and the exception below names it — issued IN THE DATABASE BEING
-- MIGRATED, because a shared catalogue's ACL is per-database. Measured the same
-- day: granted in one database, the privilege check on `pg_authid` answers
-- true there and false in the next database of the same cluster.
--
-- THE CASE THIS BLOCK EXISTS FOR: `kumbuka_dispatch` may already be there. The
-- dispatch service's own chain creates its runtime role, and a cluster where
-- that service has migrated already holds the name. Measured:
--
--     ALTER ROLE kumbuka_logbook RENAME TO kumbuka_dispatch
--     ERROR:  role "kumbuka_dispatch" already exists
--
-- An unconditional rename would therefore stop the container from booting on
-- exactly the installations that are furthest along. So the four states are
-- separated, and the collision is reported as a WARNING rather than raised: two
-- roles existing is not a defect — `kumbuka_dispatch` is already the right one
-- and `kumbuka_logbook` is a leftover — and a leftover role is not worth an
-- outage. Retiring it needs to know who still connects as it, which is a
-- deployment question and not this migration's to answer.
-- ---------------------------------------------------------------------------
DO $do$
DECLARE
    has_old   boolean;
    has_new   boolean;
    verifier  text;
BEGIN
    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_logbook'),
           EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_dispatch')
      INTO has_old, has_new;

    IF has_old AND NOT has_new THEN
        -- md5-guard-begin — case `red-md5guard` deletes this block, marker line
        -- to marker line, and measures that the rename then goes through and
        -- takes the password with it. The markers make that removal exact
        -- rather than a regex over shifting line numbers, and neither marker
        -- word appears anywhere else in this file: naming the closing one here
        -- would end the deleted range on THIS line and leave the guard standing
        -- while the probe reported it gone. (Measured: it did, once.)
        IF NOT pg_catalog.has_table_privilege('pg_catalog.pg_authid', 'SELECT') THEN
            RAISE EXCEPTION
                'V24: cannot read pg_catalog.pg_authid as "%", so whether role '
                '"kumbuka_logbook" carries an MD5 password verifier is unknown — '
                'and renaming a role DELETES an MD5 verifier, because it is '
                'salted with the role name. Refusing to rename blind: it would '
                'leave the dispatch service holding a role it can no longer '
                'authenticate as, with a clean migration log and no error to '
                'show for it.', current_user
                USING ERRCODE = 'P0001',
                      HINT = 'Migrate as a superuser (what the deployment does '
                             'today), or let the migrator read the catalogue, '
                             'CONNECTED TO THIS DATABASE — the ACL of a shared '
                             'catalogue is per-database (measured): '
                             'GRANT SELECT ON pg_catalog.pg_authid TO "'
                             || current_user || '"; — then migrate again. '
                             'pg_roles is no substitute: its rolpassword column '
                             'is the constant ''********'' for every role.';
        END IF;

        SELECT a.rolpassword INTO verifier
          FROM pg_catalog.pg_authid a
         WHERE a.rolname = 'kumbuka_logbook';

        IF verifier LIKE 'md5%' THEN
            RAISE EXCEPTION
                'V24: role "kumbuka_logbook" carries an MD5 password verifier, '
                'and renaming it to "kumbuka_dispatch" would DELETE that '
                'password — an MD5 verifier is salted with the role name, so '
                'Postgres cannot carry it across and clears it with a NOTICE '
                'the migration log will not stop for. The rename is refused '
                'rather than leaving the dispatch service unable to '
                'authenticate.'
                USING ERRCODE = 'P0001',
                      HINT = 'Set the password again under SCRAM, as the same '
                             'secret the dispatch service already uses: '
                             'SET password_encryption = ''scram-sha-256''; '
                             'ALTER ROLE kumbuka_logbook PASSWORD ''<the '
                             'existing password>''; — then migrate again. A '
                             'SCRAM verifier survives the rename (measured).';
        END IF;
        -- md5-guard-end

        ALTER ROLE kumbuka_logbook RENAME TO kumbuka_dispatch;
    ELSIF has_old AND has_new THEN
        RAISE WARNING 'V24: both kumbuka_logbook and kumbuka_dispatch exist — '
                      'the rename is skipped and kumbuka_dispatch is granted as '
                      'the dispatch service role. kumbuka_logbook is now a '
                      'leftover; retiring it needs to know who still connects '
                      'as it and is not this migration''s decision.';
    ELSIF NOT has_new THEN
        -- Neither name is present: a database that never saw V21's role block,
        -- or one where it was dropped. Same shape as V21/V22 — LOGIN, explicitly
        -- NOT BYPASSRLS, placeholder password replaced by the init script.
        CREATE ROLE kumbuka_dispatch LOGIN PASSWORD 'change-me-kumbuka-dispatch';
    END IF;
END
$do$;

GRANT USAGE ON SCHEMA platform TO kumbuka_dispatch;


-- ---------------------------------------------------------------------------
-- 2. the alias resolver's role.
--
-- NOINHERIT so that a member of it (the migrator, which holds ADMIN OPTION on a
-- role it created) does not carry its privileges implicitly; NOLOGIN so nothing
-- connects as it; explicitly NOT BYPASSRLS, because the point is a policy that
-- names it, not a role that ignores policies.
-- ---------------------------------------------------------------------------
-- The SET grant at the end is what lets `ALTER FUNCTION … OWNER TO` below work
-- under the migrator stage F intends: a CREATEROLE role that is NOT a superuser.
-- Measured on Postgres 16:
--
--   as a CREATEROLE non-superuser, after CREATE ROLE r NOLOGIN NOINHERIT:
--       pg_auth_members  ->  admin=true, inherit=false, SET=FALSE
--       ALTER FUNCTION f() OWNER TO r
--       ERROR:  must be able to SET ROLE "r"
--
-- The creator is made an ADMIN of the role it created but not a SET-holder, and
-- ownership transfer needs SET. With ADMIN in hand the creator may grant itself
-- the missing option, which is what happens here — and if the role was created
-- by somebody ELSE, that grant is refused loudly ("Only roles with the ADMIN
-- option … may grant this role") rather than leaving a function whose owner is
-- silently wrong. A superuser migrator holds everything implicitly and the
-- condition simply does not fire.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_alias_resolver') THEN
        CREATE ROLE kumbuka_alias_resolver NOLOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS;
    END IF;

    IF NOT pg_catalog.pg_has_role(current_user, 'kumbuka_alias_resolver', 'SET') THEN
        EXECUTE format('GRANT kumbuka_alias_resolver TO %I WITH SET TRUE, INHERIT FALSE',
                       current_user);
    END IF;
END
$do$;

GRANT USAGE ON SCHEMA platform TO kumbuka_alias_resolver;

-- Column-level, so the grant cannot read a tenant's name even by accident.
GRANT SELECT (tenant_id, alias) ON platform.team TO kumbuka_alias_resolver;

-- The one policy, addressed to the one role. `USING (true)` is the whole of it:
-- the row restriction for every other role is unchanged, because a policy with
-- a TO clause simply does not apply to anybody else. V3's
-- `team_tenant_isolation` stays exactly as it was and keeps being the only
-- policy the core and the provider ever meet.
DROP POLICY IF EXISTS team_alias_resolution ON platform.team;
CREATE POLICY team_alias_resolution ON platform.team
    FOR SELECT TO kumbuka_alias_resolver
    USING (true);


-- ---------------------------------------------------------------------------
-- 3. platform.tenant_id_by_alias — the published alias resolution.
--
-- Returns the tenant id for a known alias and NULL for an unknown one, and
-- discloses nothing else: no name, no count, no way to tell a disabled tenant
-- from an absent one. The pinned search_path carries `pg_catalog` first so no
-- caller-influenced path can shadow an operator or a function it uses, and
-- `platform` second for `team` itself; `public` is deliberately NOT on it — the
-- inventory lives in `platform` since V23 and a path that also named `public`
-- would resolve a `public.team` left behind by a half-finished move.
--
-- The OLD function, `public.team_tenant_id_by_alias`, is left exactly as it is.
-- It is on the hot path of every tenant-scoped request in the deployed core,
-- and switching the core onto this one is a separate step with its own release.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform.tenant_id_by_alias(alias text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, platform
AS $$
    SELECT t.tenant_id FROM team t WHERE t.alias = tenant_id_by_alias.alias
$$;

-- The ownership transfer, and the privilege it borrows for the length of one
-- statement.
--
-- Postgres requires the NEW OWNER to hold CREATE on the schema the object lives
-- in — an owner that could not have created the object there may not be handed
-- one. USAGE is not enough. Measured under the stage-F migrator (CREATEROLE,
-- not a superuser):
--
--     ALTER FUNCTION platform.tenant_id_by_alias(text) OWNER TO kumbuka_alias_resolver
--     ERROR:  permission denied for schema platform
--
-- A SUPERUSER migrator is exempt from that check, so this defect is invisible to
-- any probe that migrates as one — the shell suite under
-- `deploy/read-contract/test` runs as `postgres` and stayed green through it;
-- `MigrationCallbackWitnessIT`, which migrates as the unprivileged role stage F
-- intends, is what turned red. The same blindness is why this migration's
-- acceptance is probed as the service roles rather than as the migrator.
--
-- So CREATE is granted, used, and taken back in three statements. What the
-- resolver is left holding afterwards is USAGE on the schema, SELECT on two
-- columns of one table, and this function — it cannot create anything in
-- `platform`, and the window in which it could is this migration's own
-- transaction.
GRANT CREATE ON SCHEMA platform TO kumbuka_alias_resolver;
ALTER FUNCTION platform.tenant_id_by_alias(text) OWNER TO kumbuka_alias_resolver;
REVOKE CREATE ON SCHEMA platform FROM kumbuka_alias_resolver;

REVOKE ALL ON FUNCTION platform.tenant_id_by_alias(text) FROM PUBLIC;

-- One statement, four grantees — the symmetry is the point: every service sits
-- behind the same contract, so a later reader can see at a glance that no role
-- was forgotten and none was privileged over another.
--
-- The three service roles are named literally, because this chain creates them
-- and they are therefore certain to exist. The CORE's runtime role is not: the
-- chain never creates it, and its name differs by installation — `kumbuka` in
-- the deployment, the superuser app account under DevServices, something else
-- again in a self-hosted cluster. Naming it literally is how this migration
-- first failed:
--
--     ERROR: role "kumbuka" does not exist
--
-- So it is read out of the catalogue instead, exactly the way V23 reads it to
-- decide who gets USAGE on the schema: the core's role IS the owner of the
-- inventory, by construction. That keeps the four grantees in one statement and
-- keeps the statement correct on an installation this file cannot see.
DO $do$
DECLARE
    core_role name;
BEGIN
    SELECT pg_get_userbyid(c.relowner)
      INTO core_role
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'platform'
       AND c.relname = 'scope';

    IF core_role IS NULL THEN
        RAISE EXCEPTION
            'V24: platform.scope is absent — refusing to guess the core''s runtime role'
            USING ERRCODE = 'P0001';
    END IF;

    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) '
        'TO %I, kumbuka_worklist, kumbuka_dispatch, kumbuka_memory', core_role);
END
$do$;


-- ---------------------------------------------------------------------------
-- 4. platform.scope_access — the same four columns, three more answers.
--
-- CREATE OR REPLACE, not DROP + CREATE: the existing columns keep their names,
-- their types and their order, the new ones are appended, and the grants V21
-- and V22 issued survive untouched (a DROP would take them with it and a reader
-- would have to trust that they were all reissued).
--
--   kind       — 'project' | 'private' | 'global'. The filter that hid the
--                other two is gone; a service addresses all three now.
--   locked     — the content lock, published beside `archived`. The two are
--                different refusals and a service must be able to say which:
--                archived is retired, locked is frozen.
--   can_write  — the calling subject's write right OVER A SERVICE CHANNEL,
--                derived from MemberWritePolicy (see the header).
--
-- Visibility, per kind:
--   project  — an active member of the scope's tenant, as before.
--   global   — the same. It is the tenant-wide scope; every active member has
--              it.
--   private  — its author, where one is recorded; the autorless per-tenant
--              private scope for every active member (see the header).
-- And never, for any kind, a scope of another tenant: the tenant predicate sits
-- in the view's own definition and holds whether or not a policy reaches it.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW platform.scope_access AS
    SELECT s.id        AS scope_id,
           s.tenant_id AS tenant_id,
           s.slug      AS slug,
           s.archived  AS archived,
           s.kind      AS kind,
           s.locked    AS locked,
           -- A muted member keeps their private scope and loses shared writes;
           -- a locked scope refuses every service-channel write, whatever the
           -- member's role.
           (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write
    FROM platform.scope s
    JOIN platform.user_account ua ON ua.tenant_id = s.tenant_id
    WHERE s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
      AND ua.subject  = NULLIF(current_setting('app.subject',   true), '')
      AND ua.status   = 'active'
      AND (s.kind <> 'private'
           OR s.created_by IS NULL
           OR s.created_by = NULLIF(current_setting('app.subject', true), ''));


-- ---------------------------------------------------------------------------
-- 5. the grants on the view, reissued symmetrically.
--
-- `CREATE OR REPLACE VIEW` preserves what was there, so three of these four are
-- no-ops on an installation that ran V21 and V22. They are here anyway, in one
-- statement, because the set of services holding this contract is the thing a
-- reader needs to see — and because a cluster where the dispatch service's role
-- arrived under its own name (the collision case in step 1) holds no grant from
-- V21 at all.
-- ---------------------------------------------------------------------------
GRANT SELECT ON platform.scope_access
    TO kumbuka_worklist, kumbuka_dispatch, kumbuka_memory;
