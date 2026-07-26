-- ===========================================================================
-- V19 — content-unit channel and deleted flag.
--
-- Two changes to the `memory` head shape:
--   1. The source-channel CHECKs learn a fourth value, 'import' — the channel
--      of a bulk ingestion write. The write paths of this build do not emit it
--      yet; the constraint accepts it so rows written through a newer binary
--      remain readable and re-writable side by side with this one.
--   2. The two-valued lifecycle column `existence` ('active'|'deleted') is
--      replaced by a plain BOOLEAN `is_deleted` (default false). Same axis,
--      simpler carrier. The column is unmapped on the Java side and carries
--      only its default; the partial unique indexes are its sole readers.
--
-- This migration is idempotent with respect to its own effects: it reaches the
-- same end state whether it runs against a pristine database (existence column
-- still present, indexes on the existence predicate, narrow channel CHECKs) OR
-- against a database where part of this change was already applied out of band
-- (existence already dropped and is_deleted already present, the two partial
-- unique indexes possibly already rebuilt on the boolean predicate or possibly
-- missing, the channel CHECKs still narrow). Every step is gated by an explicit
-- catalog check or an IF [NOT] EXISTS clause — never a swallowed exception — so
-- no step fails on a shape it has already reached and the end state is identical.
--
-- Pure DDL apart from the guard below; no tenant context is required.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. Fail-loud guard. Step 3 below REPLACES the `existence` column (drop and
--    re-add as a boolean) instead of converting values in place; that is only
--    admissible while no row carries a non-default value. This guard turns that
--    assumption into a checked precondition: a database where any row has left
--    the default state aborts the migration instead of silently losing the flag.
--    The check can only run while `existence` still exists, so it is made
--    conditional on the column's presence: on a pristine database it enforces
--    the precondition; on a database where the swap already happened there is
--    nothing left to protect and it is skipped. The guard is NOT removed — a
--    migration that silently drops its own safety check is worse than one that
--    never had it.
-- ---------------------------------------------------------------------------
DO $$
DECLARE n bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = 'memory'::regclass
                 AND attname  = 'existence'
                 AND NOT attisdropped) THEN
        SELECT count(*) INTO n FROM memory WHERE existence <> 'active';
        IF n > 0 THEN
            RAISE EXCEPTION
                'migration aborted: % row(s) are not in the active state; the column is assumed empty of non-default values', n
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. Admit 'import' on both channel CHECKs. `memory_source_check` has carried
--    its explicit name since it was first widened; the CHECK on `updated_source`
--    was born inline and got the auto-generated name `memory_updated_source_check`.
--    Dropping (if present) and re-adding under the same explicit name is
--    idempotent: a database that still has the narrow constraint and a database
--    that already has the widened one both end with the widened, explicitly
--    named form.
-- ---------------------------------------------------------------------------
ALTER TABLE memory DROP CONSTRAINT IF EXISTS memory_source_check;
ALTER TABLE memory ADD  CONSTRAINT memory_source_check
    CHECK (source IN ('console','mcp','system','import'));

ALTER TABLE memory DROP CONSTRAINT IF EXISTS memory_updated_source_check;
ALTER TABLE memory ADD  CONSTRAINT memory_updated_source_check
    CHECK (updated_source IN ('console','mcp','system','import'));

-- ---------------------------------------------------------------------------
-- 3. `existence` -> `is_deleted`. The drop-and-swap half is conditional on
--    `existence` still being present. The two partial unique indexes are the
--    only readers of the column, so they are dropped first, then the column is
--    swapped. On a database where the swap already happened this block is a
--    no-op. The auto-generated CHECK on `existence` goes down with its column.
--    The delete-block trigger reads only `lock` and is untouched.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = 'memory'::regclass
                 AND attname  = 'existence'
                 AND NOT attisdropped) THEN
        DROP INDEX IF EXISTS uq_memory_shared_key;
        DROP INDEX IF EXISTS uq_memory_private_key;
        ALTER TABLE memory DROP COLUMN existence;
        ALTER TABLE memory ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4. (Re)build the two partial unique indexes under their existing names with
--    the boolean predicate. IF NOT EXISTS reaches the same end state from every
--    starting point: on a pristine database step 3 dropped the old indexes, so
--    these build the new ones; on a database that already rebuilt them by hand
--    (verbatim, same predicate) they are left untouched; on a database where
--    they are missing entirely they are created.
-- ---------------------------------------------------------------------------
-- shared (global / project): one canonical live head per key, author-independent.
CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_shared_key
    ON memory (scope_id, key)
    WHERE is_head AND NOT is_deleted AND NOT is_private AND key IS NOT NULL;

-- private: per author — two owners' identical private keys coexist.
CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_private_key
    ON memory (scope_id, owner_subject, key)
    WHERE is_head AND NOT is_deleted AND is_private AND key IS NOT NULL;
