-- ===========================================================================
-- V20 — Drop the built-in-guidance delete-block trigger and its function.
--
-- The built-in guidance entries are no longer stored as table rows: they are
-- served as a virtual read-layer overlay from a versioned resource. With no
-- protected rows ever written to `memory`, the BEFORE DELETE trigger that made
-- such rows undeletable, and the function it invoked, are dead weight. This
-- migration removes both so the schema no longer carries a guard for a class of
-- row that is never created.
--
-- Order is binding: the trigger depends on the function, so the trigger is
-- dropped first. No CASCADE — the drops are explicit and scoped to these two
-- objects only. Both statements use IF EXISTS so this migration reaches the same
-- end state whether or not the objects were already removed by hand on a running
-- database; a second application is a clean no-op.
--
-- The `lock` column, its CHECK constraint, and the 'system' source channel are
-- intentionally left in place — they carry the overlay's read-layer signature
-- and are not part of the delete-block being removed.
-- ===========================================================================

DROP TRIGGER  IF EXISTS memory_protected_delete_block ON memory;
DROP FUNCTION IF EXISTS memory_block_protected_delete();
