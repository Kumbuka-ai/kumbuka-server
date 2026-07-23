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
-- Pure DDL apart from the guard below; no tenant context is required.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. Fail-loud guard. Step 3 below REPLACES the `existence` column (drop and
--    re-add as a boolean) instead of converting values in place; that is only
--    admissible while no row carries a non-default value. This guard turns
--    that assumption into a checked precondition: a database where any row
--    has left the default state aborts the migration instead of silently
--    losing the flag.
-- ---------------------------------------------------------------------------
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM memory WHERE existence <> 'active';
    IF n > 0 THEN
        RAISE EXCEPTION
            'migration aborted: % row(s) are not in the active state; the column is assumed empty of non-default values', n
            USING ERRCODE = 'P0001';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. Admit 'import' on both channel CHECKs. `memory_source_check` has carried
--    its explicit name since it was first widened; the CHECK on
--    `updated_source` was born inline and got the auto-generated name
--    `memory_updated_source_check` (read from pg_constraint, not assumed).
--    Re-adding it under the same, now explicit, name makes the name stable
--    for any future widening.
-- ---------------------------------------------------------------------------
ALTER TABLE memory DROP CONSTRAINT memory_source_check;
ALTER TABLE memory ADD  CONSTRAINT memory_source_check
    CHECK (source IN ('console','mcp','system','import'));

ALTER TABLE memory DROP CONSTRAINT memory_updated_source_check;
ALTER TABLE memory ADD  CONSTRAINT memory_updated_source_check
    CHECK (updated_source IN ('console','mcp','system','import'));

-- ---------------------------------------------------------------------------
-- 3. `existence` -> `is_deleted`. The two partial unique indexes are the only
--    readers of the column (verified against the catalog: no function, no
--    trigger, no Java mapping touches it), so the order is: drop the indexes,
--    swap the column, rebuild the indexes under their existing names with the
--    boolean predicate. The auto-generated CHECK on `existence` goes down
--    with its column. The delete-block trigger reads only `lock` and is
--    untouched.
-- ---------------------------------------------------------------------------
DROP INDEX uq_memory_shared_key;
DROP INDEX uq_memory_private_key;

ALTER TABLE memory DROP COLUMN existence;
ALTER TABLE memory ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;

-- shared (global / project): one canonical live head per key, author-independent.
CREATE UNIQUE INDEX uq_memory_shared_key
    ON memory (scope_id, key)
    WHERE is_head AND NOT is_deleted AND NOT is_private AND key IS NOT NULL;

-- private: per author — two owners' identical private keys coexist.
CREATE UNIQUE INDEX uq_memory_private_key
    ON memory (scope_id, owner_subject, key)
    WHERE is_head AND NOT is_deleted AND is_private AND key IS NOT NULL;
