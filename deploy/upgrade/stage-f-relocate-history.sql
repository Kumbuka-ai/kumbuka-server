-- ===========================================================================
-- stage-f-relocate-history.sql — move the Flyway history into `platform` and
-- point the roles at it, in ONE transaction.
--
-- Run this ONCE per installation, BEFORE deploying the image that carries V23.
-- The application may keep running while it does: the statements below touch
-- the history table and two role settings, and the running image reads neither
-- until its next start.
--
-- WHY THIS IS NOT A MIGRATION
--
-- Because a migration cannot do it. Flyway holds an AccessShareLock on
-- `flyway_schema_history` in a second connection for the whole of a migrate
-- run, so an `ALTER TABLE ... SET SCHEMA` inside a migration waits on Flyway
-- itself. With `lock_timeout = 0` — the shipped default — it waits forever, and
-- because migrations run at container start, the container never finishes
-- booting and never fails either. Measured against Flyway 12.0.0 before
-- this was written.
--
-- WHY THE HISTORY AND THE SEARCH_PATH MOVE TOGETHER
--
-- Flyway, configured with neither `schemas` nor `defaultSchema`, looks for its
-- history in `current_schema()` — the first entry of the migrator's
-- search_path. So the location of the history and the migrator's search_path
-- are one fact, not two: change either alone and the next start either invents
-- a second history or fails to find the first. Moving both inside one
-- transaction is what makes the step atomic. Measured against Flyway 12.0.0: moving
-- both together is green, moving the search_path alone is not.
--
-- PARAMETERS — override with `psql -v name=value`
--   migrator  the role Flyway connects as            (default: postgres)
--   runtime   the role the application connects as   (default: kumbuka)
--   db        the database the setting is scoped to  (default: kumbuka)
--
-- The migrator's setting is scoped `IN DATABASE`, the runtime role's is not.
-- That asymmetry is deliberate: the migrator is usually the cluster superuser
-- and the cluster carries other databases, so pinning its search_path globally
-- would reach into every one of them. The runtime role exists for this
-- application alone.
--
-- WHAT IT REFUSES TO DO
--
--   * both histories present  — a half-finished earlier attempt. There is no
--     safe automatic answer to which one is real, so it stops and changes
--     nothing.
--   * no history at all       — this is not an installation that has ever run
--     the chain. Running here would produce role settings pointing at a schema
--     with no history in it, and the next start would then create one from
--     scratch. It stops.
--   * the history is locked   — a Flyway run is in progress. It stops rather
--     than queue behind it.
--
-- Running it a second time after it succeeded is safe: it reports that the
-- history is already in place and changes nothing.
-- ===========================================================================

\set ON_ERROR_STOP on

\if :{?migrator} \else \set migrator postgres \endif
\if :{?runtime}  \else \set runtime  kumbuka  \endif
\if :{?db}       \else \set db       kumbuka  \endif

BEGIN;

-- Bounded, so a Flyway run in progress produces a refusal instead of a queue.
SET LOCAL lock_timeout = '5s';

SELECT set_config('kumbuka.stage_f.migrator', :'migrator', true),
       set_config('kumbuka.stage_f.runtime',  :'runtime',  true),
       set_config('kumbuka.stage_f.db',       :'db',       true);

DO $stage_f$
DECLARE
    migrator    name    := current_setting('kumbuka.stage_f.migrator');
    runtime     name    := current_setting('kumbuka.stage_f.runtime');
    db          name    := current_setting('kumbuka.stage_f.db');
    in_public   boolean := to_regclass('public.flyway_schema_history')   IS NOT NULL;
    in_platform boolean := to_regclass('platform.flyway_schema_history') IS NOT NULL;
BEGIN
    IF to_regnamespace('platform') IS NULL THEN
        RAISE EXCEPTION
            'stage F: schema "platform" does not exist — this installation has not reached V21. Nothing was changed.'
            USING ERRCODE = 'P0001';
    END IF;

    IF in_public AND in_platform THEN
        RAISE EXCEPTION
            'stage F: flyway_schema_history exists in BOTH public and platform — half-finished relocation. Decide which one is real by hand; nothing was changed.'
            USING ERRCODE = 'P0001';
    END IF;

    IF NOT in_public AND NOT in_platform THEN
        RAISE EXCEPTION
            'stage F: no flyway_schema_history in public or platform — this database has never run the migration chain. Nothing was changed.'
            USING ERRCODE = 'P0001';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = migrator) THEN
        RAISE EXCEPTION 'stage F: migrator role % does not exist. Nothing was changed.', migrator
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = runtime) THEN
        RAISE EXCEPTION 'stage F: runtime role % does not exist. Nothing was changed.', runtime
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = db) THEN
        RAISE EXCEPTION 'stage F: database % does not exist. Nothing was changed.', db
            USING ERRCODE = 'P0001';
    END IF;

    -- The database this file is CONNECTED to and the one `db` names must be
    -- the same, and until sprint/186.5 nothing checked it.
    --
    -- Everything above and below reads and writes the CONNECTED database:
    -- to_regclass, to_regnamespace, and the ALTER TABLE that moves the
    -- history. Only `ALTER ROLE ... IN DATABASE` uses `db`. The existence
    -- check alone does not catch a mismatch — it passes for any database that
    -- happens to exist, and `db` carries the silent default `kumbuka`.
    --
    -- Measured 2026-09-20 against PostgreSQL 16: connected to `kumbuka_prod`
    -- with `db` left at its default, the run reported success while the
    -- history moved in `kumbuka_prod` and the search_path setting landed on
    -- `kumbuka`. That is exactly the split this file's own header calls the
    -- dangerous one — "change either alone and the next start either invents a
    -- second history or fails to find the first" — produced by a run that says
    -- it succeeded, and with baseline-on-migrate off the next start refuses.
    -- The revert does not help, because it inherits the same split.
    IF db <> current_database() THEN
        RAISE EXCEPTION
            'stage F: db=% but this session is connected to % — the history would move in one database and the search_path setting land on the other. Pass -v db=% , or connect to %. Nothing was changed.',
            db, current_database(), current_database(), db
            USING ERRCODE = 'P0001';
    END IF;

    IF in_platform THEN
        RAISE NOTICE 'stage F: flyway_schema_history is already in platform and absent from public — nothing to do.';
        RETURN;
    END IF;

    BEGIN
        EXECUTE 'ALTER TABLE public.flyway_schema_history SET SCHEMA platform';
    EXCEPTION WHEN lock_not_available THEN
        RAISE EXCEPTION
            'stage F: could not lock public.flyway_schema_history within the lock timeout — a Flyway run is holding it. Stop the application container and run this again. Nothing was changed.'
            USING ERRCODE = 'P0001';
    END;

    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = platform, public', migrator, db);
    EXECUTE format('ALTER ROLE %I SET search_path = platform, public', runtime);
    EXECUTE format('GRANT USAGE ON SCHEMA platform TO %I', runtime);

    RAISE NOTICE 'stage F: history moved to platform; search_path set for % (in database %) and for %; % granted USAGE on platform.',
        migrator, db, runtime, runtime;
END
$stage_f$;

COMMIT;
