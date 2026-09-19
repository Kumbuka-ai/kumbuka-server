-- ===========================================================================
-- stage-f-relocate-history-revert.sql — the way back from
-- stage-f-relocate-history.sql, in ONE transaction.
--
-- It undoes exactly what the relocation did: it moves `flyway_schema_history`
-- from `platform` back to `public`, drops the two search_path settings, and
-- takes the runtime role's USAGE on `platform` away again. Afterwards the
-- database is in the state the relocation found it in — measured, not assumed:
-- before stage F neither the migrator nor the runtime role has a row in
-- `pg_db_role_setting`, and the runtime role has no USAGE on `platform`
-- (sprint/185.1 part B).
--
-- WHEN IT IS THE ANSWER, AND WHEN IT IS NOT
--
-- The rollout has three steps: stop nothing, run the relocation (step 2),
-- deploy the image that carries V23 (step 3). Between 2 and 3 there is a
-- window in which the database has already moved and the OLD image is still
-- what would come back up on a restart — and the old image does not survive
-- that restart, because Hibernate validates its unqualified entities against
-- ONE default schema and the relocation has already pointed that schema at
-- `platform` while the tables are still in `public` (measured in sprint/185.2,
-- deviation 2). This file closes that window.
--
-- Once V23 HAS been applied it is not the answer and says so. The tables are
-- in `platform` from then on, so moving the history back would leave a
-- database whose history says V23 ran and whose search_path cannot find what
-- V23 created. Worse, the way back by image swap is not open at all after an
-- applied migration: Flyway 12 refuses to migrate against a database carrying
-- an applied version it cannot resolve locally ("Detected applied migration
-- not resolved locally: 23"), and that holds for ANY release that adds a
-- migration. That is a property of the whole chain and is not this file's to
-- solve; it is named here so nobody reaches for this file expecting it.
--
-- PARAMETERS — override with `psql -v name=value`
--   migrator  the role Flyway connects as            (default: postgres)
--   runtime   the role the application connects as   (default: kumbuka)
--   db        the database the setting is scoped to  (default: kumbuka)
--
-- WHAT IT REFUSES TO DO — always before changing anything, the file being one
-- transaction:
--
--   * V23 is applied       — see above. Forward only from here.
--   * history in both      — a half-finished move in either direction. There
--     schemas                is no safe automatic answer to which one is real.
--   * history in neither   — nothing to move back.
--   * a search_path that   — the relocation sets exactly `platform, public`.
--     is not the one the     Anything else was set by somebody else, and a
--     relocation sets        RESET would silently delete their setting.
--   * more than one        — a role should carry at most one. Which of several
--     search_path entry      a RESET would remove is not decidable here.
--   * the history is       — a Flyway run holds it. It stops rather than
--     locked                 queue behind it.
--
-- Running it when the database is already back is safe: it reports that and
-- changes nothing.
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

DO $stage_f_revert$
DECLARE
    migrator    name    := current_setting('kumbuka.stage_f.migrator');
    runtime     name    := current_setting('kumbuka.stage_f.runtime');
    db          name    := current_setting('kumbuka.stage_f.db');
    in_public   boolean := to_regclass('public.flyway_schema_history')   IS NOT NULL;
    in_platform boolean := to_regclass('platform.flyway_schema_history') IS NOT NULL;

    -- What the relocation writes, character for character. Measured against
    -- PostgreSQL 16: `ALTER ROLE x SET search_path = platform, public` is
    -- stored as `search_path=platform, public` — one space after the comma,
    -- none around the equals sign.
    expected    text    := 'search_path=platform, public';

    history      text;
    v23_applied  boolean;
    migrator_sps text[];
    runtime_sps  text[];
    migrator_sp  text;
    runtime_sp   text;
    touched      boolean := false;
BEGIN
    -- ---------------------------------------------------------------- shape
    IF in_public AND in_platform THEN
        RAISE EXCEPTION
            'stage F revert: flyway_schema_history exists in BOTH public and platform — half-finished move. Decide which one is real by hand; nothing was changed.'
            USING ERRCODE = 'P0001';
    END IF;

    IF NOT in_public AND NOT in_platform THEN
        RAISE EXCEPTION
            'stage F revert: no flyway_schema_history in public or platform — there is nothing to move back. Nothing was changed.'
            USING ERRCODE = 'P0001';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = migrator) THEN
        RAISE EXCEPTION 'stage F revert: migrator role % does not exist. Nothing was changed.', migrator
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = runtime) THEN
        RAISE EXCEPTION 'stage F revert: runtime role % does not exist. Nothing was changed.', runtime
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = db) THEN
        RAISE EXCEPTION 'stage F revert: database % does not exist. Nothing was changed.', db
            USING ERRCODE = 'P0001';
    END IF;

    -- --------------------------------------------------------- forward only
    --
    -- Two independent signs, because either alone can be the one that is
    -- there: the history row is what Flyway goes by, and the relation is what
    -- the application goes by. A database carrying one without the other is
    -- broken in a way this file must not drive over.
    history := CASE WHEN in_platform THEN 'platform.flyway_schema_history'
                                     ELSE 'public.flyway_schema_history' END;

    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %s WHERE version = ''23'')', history)
        INTO v23_applied;

    IF v23_applied OR to_regclass('platform.scope') IS NOT NULL THEN
        RAISE EXCEPTION
            'stage F revert: V23 is applied (%) — from here the way is forward only. Moving the history back would leave a database whose history says V23 ran and whose search_path cannot find what V23 created; and an image swap is not open after an applied migration either, because Flyway refuses a migration it cannot resolve locally. Nothing was changed.',
            CASE WHEN v23_applied THEN 'a row with version 23 is in the history'
                                  ELSE 'platform.scope exists' END
            USING ERRCODE = 'P0001';
    END IF;

    -- ------------------------------------------------- somebody else's path
    --
    -- Read before anything is written. A RESET is not a subtraction of what
    -- the relocation added — it removes the whole setting — so a value this
    -- file did not put there would be destroyed rather than restored.
    --
    -- Read by NAME, not by position. `setconfig` is the role's whole list of
    -- settings and entries are appended in the order they were set, so nothing
    -- puts `search_path` first: a role that already carried, say, a
    -- `statement_timeout` when stage F ran has it at index 2. Measured against
    -- PostgreSQL 16 — `ALTER ROLE r SET statement_timeout`, then `SET
    -- search_path` — gives `{statement_timeout=31s,"search_path=platform,
    -- public"}`, and `setconfig[1]` then matches no `search_path=%` at all.
    -- Reading index 1 would leave both variables NULL: the foreign-value check
    -- below would pass on nothing, the RESET further down hangs on the same
    -- non-NULL value and would be skipped — and the run would still report the
    -- pre-stage-F state restored while the search_path stood untouched.
    SELECT array_agg(cfg) INTO migrator_sps
      FROM pg_catalog.pg_db_role_setting s
      JOIN pg_catalog.pg_roles r    ON r.oid = s.setrole
      JOIN pg_catalog.pg_database d ON d.oid = s.setdatabase
      CROSS JOIN LATERAL unnest(s.setconfig) AS cfg
     WHERE r.rolname = migrator AND d.datname = db
       AND cfg LIKE 'search_path=%';

    SELECT array_agg(cfg) INTO runtime_sps
      FROM pg_catalog.pg_db_role_setting s
      JOIN pg_catalog.pg_roles r ON r.oid = s.setrole
      CROSS JOIN LATERAL unnest(s.setconfig) AS cfg
     WHERE r.rolname = runtime AND s.setdatabase = 0
       AND cfg LIKE 'search_path=%';

    -- At most one, and if there are more the file says so rather than picking.
    -- PostgreSQL replaces rather than appends on `ALTER ROLE ... SET
    -- search_path`, so a second entry cannot come from the catalogue's own
    -- rules — and if one is there regardless, which of the two a RESET would
    -- remove is not this file's guess to make.
    IF coalesce(array_length(migrator_sps, 1), 0) > 1 THEN
        RAISE EXCEPTION
            'stage F revert: the settings of % in database % carry more than one search_path entry (%). Which one a RESET would remove is not decidable here. Nothing was changed.',
            migrator, db, array_to_string(migrator_sps, ' | ')
            USING ERRCODE = 'P0001';
    END IF;

    IF coalesce(array_length(runtime_sps, 1), 0) > 1 THEN
        RAISE EXCEPTION
            'stage F revert: the settings of % carry more than one search_path entry (%). Which one a RESET would remove is not decidable here. Nothing was changed.',
            runtime, array_to_string(runtime_sps, ' | ')
            USING ERRCODE = 'P0001';
    END IF;

    migrator_sp := migrator_sps[1];
    runtime_sp  := runtime_sps[1];

    IF migrator_sp IS NOT NULL AND migrator_sp <> expected THEN
        RAISE EXCEPTION
            'stage F revert: the search_path of % in database % is "%", not the "%" the relocation sets. Somebody else set it, and RESET would delete their setting rather than restore yours. Nothing was changed.',
            migrator, db, migrator_sp, expected
            USING ERRCODE = 'P0001';
    END IF;

    IF runtime_sp IS NOT NULL AND runtime_sp <> expected THEN
        RAISE EXCEPTION
            'stage F revert: the search_path of % is "%", not the "%" the relocation sets. Somebody else set it, and RESET would delete their setting rather than restore yours. Nothing was changed.',
            runtime, runtime_sp, expected
            USING ERRCODE = 'P0001';
    END IF;

    -- --------------------------------------------------------- the way back
    --
    -- Each part is conditional on its own, so a database that is partly back
    -- — which one transaction cannot produce, but a hand can — is finished
    -- rather than refused.
    IF in_platform THEN
        BEGIN
            EXECUTE 'ALTER TABLE platform.flyway_schema_history SET SCHEMA public';
        EXCEPTION WHEN lock_not_available THEN
            RAISE EXCEPTION
                'stage F revert: could not lock platform.flyway_schema_history within the lock timeout — a Flyway run is holding it. Stop the application container and run this again. Nothing was changed.'
                USING ERRCODE = 'P0001';
        END;
        touched := true;
    END IF;

    IF migrator_sp IS NOT NULL THEN
        EXECUTE format('ALTER ROLE %I IN DATABASE %I RESET search_path', migrator, db);
        touched := true;
    END IF;

    IF runtime_sp IS NOT NULL THEN
        EXECUTE format('ALTER ROLE %I RESET search_path', runtime);
        touched := true;
    END IF;

    -- Conditional, though a REVOKE of a privilege that is not held would be no
    -- error either. The condition is not there to make the statement safe; it
    -- is there for the report. `touched` is what decides between the two
    -- notices below, so a run that found no USAGE to take back must not raise
    -- it — otherwise a database that was already in the pre-stage-F state
    -- would be told the revert had done something. One catalogue lookup is
    -- what that costs.
    IF has_schema_privilege(runtime, 'platform', 'USAGE') THEN
        EXECUTE format('REVOKE USAGE ON SCHEMA platform FROM %I', runtime);
        touched := true;
    END IF;

    IF touched THEN
        RAISE NOTICE 'stage F revert: history is back in public; search_path reset for % (in database %) and for %; % no longer has USAGE on platform. The previous image can start again.',
            migrator, db, runtime, runtime;
    ELSE
        RAISE NOTICE 'stage F revert: the database is already in the pre-stage-F state — history in public, no search_path settings, no USAGE on platform. Nothing to do.';
    END IF;
END
$stage_f_revert$;

COMMIT;
