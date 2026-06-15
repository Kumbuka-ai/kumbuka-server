-- ===========================================================================
-- V12 — D-CORE-11: protected system-seed mnemonics.
--
-- Adds a `protected` attribute that is structurally undeletable below the
-- application layer + records the new `system` source channel used by the
-- provisioning seeder. The two changes ship together because they only make
-- sense paired: protected rows are written exclusively by the system identity,
-- and the delete-lock is the property that makes the seed unfalsifiable
-- after the fact.
--
-- The application-layer guards live in MemoryRepository / MemoryTools /
-- AdminEntriesResource — but the DELETE-block is enforced HERE, in the DB,
-- because that's the only layer every role (member, admin, operator,
-- service account, even an app-layer bypass) must pass through. Mirrors
-- the UPDATE/DELETE-revoked hardening on the ops audit table (D-OPS-11)
-- and the team-admin audit-log shape (D-CORE-9): structural enforcement,
-- not an app guard.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. New column on the memory table.
--    Defaults FALSE — every existing row stays unprotected (the seeder
--    promotes the three how-to entries by key on first run; no backfill).
-- ---------------------------------------------------------------------------
ALTER TABLE memory
    ADD COLUMN protected BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- 2. Extend the source CHECK to recognise the new 'system' channel.
--    `system` is used by the provisioning seeder (and nothing else); the
--    application layer enforces that callers cannot set it via tool args.
-- ---------------------------------------------------------------------------
ALTER TABLE memory
    DROP CONSTRAINT memory_source_check;

ALTER TABLE memory
    ADD CONSTRAINT memory_source_check
    CHECK (source IN ('console', 'mcp', 'system'));

-- ---------------------------------------------------------------------------
-- 3. Structural delete-lock.
--
-- WHY A TRIGGER (NOT AN RLS POLICY):
--   The lock has to hold against EVERY caller — member, admin, operator,
--   service account, and the BYPASSRLS roles (`kumbuka_ops_reader`,
--   `kumbuka`). RLS policies are skipped by BYPASSRLS, so an RLS-based
--   lock would not protect the seed against the ops reader (which already
--   has DELETE-revoked on `memory` per D-OPS-4, but a trigger is the only
--   shape that holds even if a future grant change re-enables DELETE).
--   Triggers fire on every DELETE regardless of role, only being skipped
--   by `ALTER TABLE … DISABLE TRIGGER`, which itself requires OWNER and
--   leaves a clear audit trail.
--
-- ERROR SURFACE:
--   The exception code 'P0001' (raise_exception) carries a stable SQLSTATE
--   the MemoryRepository / memory_forget adapter pattern-matches on to
--   surface a typed PROTECTED_DELETE_BLOCKED error instead of a raw 500.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION memory_block_protected_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.protected THEN
        RAISE EXCEPTION
            'memory row is protected (id=% key=%) — protected rows are structurally undeletable (D-CORE-11)',
            OLD.id, OLD.key
            USING ERRCODE = 'P0001';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER memory_protected_delete_block
    BEFORE DELETE ON memory
    FOR EACH ROW
    EXECUTE FUNCTION memory_block_protected_delete();

-- ---------------------------------------------------------------------------
-- 4. Index on (tenant_id, scope_id, key) WHERE protected
--    Two reasons:
--    (a) the application's pre-write check ("does a protected row with this
--        key already exist?") becomes a single index lookup.
--    (b) makes the "list all protected entries in a tenant" surface (used
--        by the seeder for idempotency probing) trivially cheap.
-- ---------------------------------------------------------------------------
CREATE INDEX ix_memory_protected_key
    ON memory (tenant_id, scope_id, key)
    WHERE protected;
