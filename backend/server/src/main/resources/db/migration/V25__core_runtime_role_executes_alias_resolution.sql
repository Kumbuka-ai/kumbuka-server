-- ===========================================================================
-- V25: the core's runtime role is called `kumbuka`, and it holds EXECUTE on
--      the published alias resolution BECAUSE THIS FILE SAYS SO — not because
--      it happens to own `platform.scope` when somebody migrates.
--
-- V24 published `platform.tenant_id_by_alias(text)` and granted EXECUTE to
-- four roles. Three of them it names literally; the fourth, the core's own
-- runtime role, it reads out of the catalogue — `pg_get_userbyid(relowner)` of
-- `platform.scope` — because the chain never created that role and its name
-- differed by installation. That indirection is the defect this migration
-- corrects.
--
-- Measured against the real chain V1..V24 on postgres:16, migrated as the
-- superuser, with `platform.scope` owned by that superuser (2026-09-22, and
-- once before that against the chain as released):
--
--     SELECT has_function_privilege('kumbuka',
--              'platform.tenant_id_by_alias(text)','EXECUTE')   ->  f
--     SELECT has_schema_privilege('kumbuka','platform','USAGE')  ->  f
--     as kumbuka: SELECT platform.tenant_id_by_alias('alpha')
--         ERROR:  permission denied for schema platform
--
-- The deployment migrates the core as the superuser and connects at runtime as
-- `kumbuka` (measured in the sibling infra repository, `compose.prod.yml`:
-- the Flyway user is the cluster's POSTGRES_USER, the runtime user is
-- KUMBUKA_DB_USER). So whether V24's grant lands on the runtime role at all
-- depends on who owns the tables on the day the chain runs — and the service
-- that calls this function answers every tenant-scoped request with a 500 when
-- it does not.
--
-- The operator's decision (2026-09-21): the core's runtime role is called
-- `kumbuka` in EVERY installation. The chain creates it if it is absent and
-- names it literally, exactly as it creates and names `kumbuka_worklist`,
-- `kumbuka_dispatch` and `kumbuka_memory`. A Flyway placeholder for the role
-- name and a group role were both rejected.
--
-- V24 is left byte-identical, its own grant included: an applied migration is
-- never edited, and this one adds rather than repairs. A database where V24's
-- indirection happened to be correct simply carries the same grant twice.
--
-- The rule a runtime role is held to still holds: it owns nothing, is neither
-- a superuser nor BYPASSRLS, and carries enumerated privilege. What is handed
-- out below is exactly that — USAGE on one schema, EXECUTE on one function. No
-- ownership is moved and nothing is revoked.
--
-- ---------------------------------------------------------------------------
-- WHY THE EXECUTE GRANT IS ISSUED UNDER THE FUNCTION'S OWNER
-- ---------------------------------------------------------------------------
--
-- V24 hands the function to `kumbuka_alias_resolver`, so the migrator is NOT
-- its owner afterwards. A GRANT on somebody else's object does not raise — it
-- warns and does nothing. Measured 2026-09-22, chain V1..V24 applied by a
-- CREATEROLE non-superuser (the migrator stage F intends):
--
--     GRANT EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) TO kumbuka
--     WARNING:  no privileges were granted for "tenant_id_by_alias"
--
--     SELECT pg_has_role('stage_f','kumbuka_alias_resolver','USAGE')  ->  false
--     SELECT pg_has_role('stage_f','kumbuka_alias_resolver','SET')    ->  true
--
-- The migrator is a MEMBER of the resolver with SET and without INHERIT (V24
-- grants itself exactly that, to be allowed the ownership transfer), and
-- Postgres decides "is the owner" by INHERITED privilege. So the grant is
-- issued under the owner — `SET LOCAL ROLE` where the privileges are not
-- inherited, directly where they are (a superuser, or the owner itself).
--
-- AND THE RESULT IS READ BACK, because the failure mode above is a WARNING in
-- a migration log and nothing else: a chain that reported success while the
-- grant went nowhere is precisely how this defect reached a release. The
-- read-back asks the ACL for an entry naming `kumbuka` rather than asking
-- `has_function_privilege`, which cannot tell a grant to this role from a
-- privilege PUBLIC still holds.
--
-- Flyway reads a dollar sign followed by a braced name as a placeholder
-- everywhere in this file, comments included, and then refuses to parse the
-- migration at all. No such sequence is written here — the two environment
-- variables named above are deliberately spelled without their shell braces.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. the runtime role.
--
-- Created only where it is absent, and an EXISTING role is not touched: no
-- password, no attribute. A cluster where the app already connects as
-- `kumbuka` must keep authenticating with the secret it has, and whether an
-- existing role is shaped the way the runtime-role rule wants is a question for
-- that rule to settle, not for a grant migration — a refusal here would break
-- every development environment that runs the app as a superuser.
--
-- The password is a placeholder, the same shape V21, V22 and V24 use for the
-- service roles, and the NOTICE says so: a role created by this line can log
-- in with a secret that is written down in a public repository until the
-- deployment rotates it.
-- ---------------------------------------------------------------------------
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka') THEN
        CREATE ROLE kumbuka LOGIN NOSUPERUSER NOBYPASSRLS
            PASSWORD 'change-me-kumbuka';
        RAISE NOTICE
            'V25: created the core runtime role "kumbuka" with the placeholder '
            'password ''change-me-kumbuka''. ROTATE IT before anything connects '
            'as this role: ALTER ROLE kumbuka PASSWORD ''<the secret the '
            'deployment holds>'';';
    END IF;
END
$do$;


-- ---------------------------------------------------------------------------
-- 2. USAGE on the schema the function lives in.
--
-- Not cosmetic and not implied by the EXECUTE grant: without it the call is
-- refused before the function is ever reached ("permission denied for schema
-- platform", measured above). Idempotent, and a no-op on an installation where
-- `kumbuka` owns the schema or already holds the grant.
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA platform TO kumbuka;


-- ---------------------------------------------------------------------------
-- 3. EXECUTE on the published alias resolution, and the read-back that decides
--    whether this migration actually did anything.
-- ---------------------------------------------------------------------------
-- core-execute-begin — the red probe `red-core-grant` deletes this block,
-- marker line to marker line, and measures that `kumbuka` is then refused the
-- lookup while the rest of the chain still applies. The markers make that
-- removal exact rather than a regex over shifting line numbers; the closing
-- one is not spelled out here, because naming it on this line would end the
-- deleted range here and leave the block standing while the probe reported it
-- gone (V24 records the same trap, having fallen into it once).
DO $do$
DECLARE
    fn_owner   name;
    is_granted boolean;
BEGIN
    SELECT pg_get_userbyid(p.proowner)
      INTO fn_owner
      FROM pg_catalog.pg_proc p
      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'platform'
       AND p.proname = 'tenant_id_by_alias'
       AND p.pronargs = 1;

    IF fn_owner IS NULL THEN
        RAISE EXCEPTION
            'V25: platform.tenant_id_by_alias(text) is absent, so the grant '
            'this migration exists for has nothing to attach to. V24 creates '
            'that function; a database at V24 that does not carry it has been '
            'altered by hand.'
            USING ERRCODE = 'P0001';
    END IF;

    -- core-grant-begin — the red probe `red-core-verify` deletes this branch
    -- alone and measures that the read-back below then STOPS the migration
    -- instead of letting a chain report success with no grant issued. What is
    -- left after that cut is still valid plpgsql, which is why the branch and
    -- the read-back are separated at all.
    IF pg_catalog.pg_has_role(current_user, fn_owner, 'USAGE') THEN
        GRANT EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) TO kumbuka;
    ELSIF pg_catalog.pg_has_role(current_user, fn_owner, 'SET') THEN
        EXECUTE format('SET LOCAL ROLE %I', fn_owner);
        GRANT EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) TO kumbuka;
        RESET ROLE;
    ELSE
        RAISE EXCEPTION
            'V25: cannot grant EXECUTE on platform.tenant_id_by_alias(text) to '
            '"kumbuka" as "%": the function is owned by "%" and this role '
            'neither inherits that role''s privileges nor may SET ROLE to it. '
            'Issuing the grant anyway would only WARN and change nothing, and '
            'a migration that reports success while the runtime role holds no '
            'grant is the defect this file exists to remove.',
            current_user, fn_owner
            USING ERRCODE = 'P0001',
                  HINT = 'Migrate as a superuser (what the deployment does '
                         'today), or let the migrator act for the owner: '
                         'GRANT "' || fn_owner || '" TO "' || current_user
                         || '" WITH SET TRUE, INHERIT FALSE; — which is what '
                         'V24 arranges for the migrator that applied it.';
    END IF;
    -- core-grant-end

    -- Asked of the ACL, not of has_function_privilege: that function answers
    -- true for every role while PUBLIC still holds EXECUTE, so it cannot
    -- witness a grant to THIS role. Measured 2026-09-22 under a stage-F
    -- migrator, where V24's own `REVOKE ALL … FROM PUBLIC` is the same silent
    -- no-op as the grant above and the ACL keeps `=X/kumbuka_alias_resolver`:
    -- has_function_privilege('kumbuka', …) answered true there before this
    -- migration granted anything at all. (Reported as a finding against V24,
    -- which this migration may not edit.)
    SELECT EXISTS (
             SELECT 1
               FROM pg_catalog.pg_proc p
               JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
               CROSS JOIN LATERAL aclexplode(p.proacl) a
              WHERE n.nspname = 'platform'
                AND p.proname = 'tenant_id_by_alias'
                AND p.pronargs = 1
                AND a.grantee = 'kumbuka'::regrole
                AND a.privilege_type = 'EXECUTE')
      INTO is_granted;

    IF NOT is_granted THEN
        RAISE EXCEPTION
            'V25: the EXECUTE grant on platform.tenant_id_by_alias(text) to '
            '"kumbuka" did not land — the function''s access list carries no '
            'entry for that role. A GRANT issued by a role that is not the '
            'owner only WARNS, so the statement above can have done nothing '
            'without failing. Refusing to finish: the core would answer every '
            'tenant-scoped request with a 500.'
            USING ERRCODE = 'P0001';
    END IF;

    IF NOT pg_catalog.has_schema_privilege('kumbuka', 'platform', 'USAGE') THEN
        RAISE EXCEPTION
            'V25: "kumbuka" cannot use schema platform, so the EXECUTE grant '
            'above is unreachable — the call is refused at the schema before '
            'the function is looked up. The GRANT USAGE in section 2 did '
            'nothing, which happens when the migrator does not own the schema '
            'and does not act for its owner.'
            USING ERRCODE = 'P0001',
                  HINT = 'Migrate as a superuser, or as the owner of schema '
                         'platform, and migrate again.';
    END IF;
END
$do$;
-- core-execute-end
