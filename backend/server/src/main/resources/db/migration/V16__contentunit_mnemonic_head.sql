-- ===========================================================================
-- V16 — ContentUnit keystone, Step 1 (CE / Mnemonic head). ADR-0024 + Amend. 1.
--
-- Pre-beta, NON-migratable identity/versioning rebuild of the `memory` table.
-- This realises only the **Mnemonic** specialization of ContentUnit and only
-- what ADR-0024 §4 marks as living in BOTH tiers: the entry-head shape
-- (three-layer identity, lifecycle / existence / head-ness, lock, typed
-- relations, reserved valid-window columns). CoW in CE is head-overwrite —
-- there is NO history table, NO append-only enforcement, NO rollback/as-of
-- here (those are EE, Step 2). The only EE docking seam reserved is the stable
-- `logical_id` column (A1.2).
--
-- DDL is RLS-exempt; the backfill DML below runs under the `app.tenant_id` GUC
-- set by TenantyMigrationCallback (BEFORE_EACH_MIGRATE), so it is visible
-- through the V3 RLS policies. Ordering matters and is called out inline:
--   1. rename id -> row_id
--   2. add the new head columns (logical_id / is_private nullable first)
--   3. add `lock`, backfill it from `protected`, then drop `protected`
--   4. backfill logical_id / is_private / version / state / existence
--   5. enforce NOT NULL on the backfilled columns
--   6. fail-loud collision guard (shared-key duplicates) BEFORE the index build
--   7. replace uq_memory_key with the two scope-kind-differentiated indexes
--   8. content_relation table (+ acyclicity guard)
--   9. extend the protected trigger to DELETE + UPDATE (move/rename parity)
--  10. scope.locked (FEAT-19, reserved)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. Rename the physical row id. Nothing references memory.id (A1.3 (3),
--    empirically re-verified: no FK, scope_stats -> scope(id), governance_audit
--    stores TEXT subjects). The old id is retained as row_id (the PK and the
--    physical handle the MCP/admin surfaces address); a fresh logical_id is the
--    cross-version identity (set in the backfill).
-- ---------------------------------------------------------------------------
ALTER TABLE memory RENAME COLUMN id TO row_id;

-- ---------------------------------------------------------------------------
-- 2. New ContentUnit head columns (A1.4). logical_id + is_private are NOT NULL
--    in the final shape but have NO default (A1.4: "set in backfill"), so they
--    are added nullable here and promoted to NOT NULL in step 5.
-- ---------------------------------------------------------------------------
ALTER TABLE memory
    ADD COLUMN logical_id  UUID,                                   -- entry identity across versions (set in backfill)
    ADD COLUMN version     INT         NOT NULL DEFAULT 1,         -- monotonic per logical_id; optimistic-lock coordinate (§11)
    ADD COLUMN is_head     BOOLEAN     NOT NULL DEFAULT TRUE,      -- trivially TRUE in CE (no history); shape-parity with EE (§4)
    ADD COLUMN state       VARCHAR(16) NOT NULL DEFAULT 'published'
        CHECK (state IN ('draft','proposed','published','superseded')),  -- lifecycle (§7)
    ADD COLUMN existence   VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (existence IN ('active','deleted')),                 -- tombstone axis (§7)
    ADD COLUMN is_private  BOOLEAN,                                -- denormalized scope.kind='private', invariant (A1.3 (1a)); set in backfill
    ADD COLUMN valid_from  TIMESTAMPTZ,                            -- reserved, NOT enforced in CE (§6)
    ADD COLUMN valid_until TIMESTAMPTZ;                            -- reserved, NOT enforced in CE (§6)

-- UNIQUE(logical_id, version) — the §6 version coordinate. PK stays row_id.
ALTER TABLE memory
    ADD CONSTRAINT uq_memory_logical_version UNIQUE (logical_id, version);

-- ---------------------------------------------------------------------------
-- 3. `lock` replaces `protected` (§13). Add it (default 'none'), backfill from
--    protected, THEN drop protected (and its index) — strictly after the read.
--    'system' = the D-CORE-11 system-seed lock; 'admin' is reserved (D-CORE-13,
--    not enforced here); 'none' = ordinary row.
-- ---------------------------------------------------------------------------
ALTER TABLE memory
    ADD COLUMN lock VARCHAR(16) NOT NULL DEFAULT 'none'
        CHECK (lock IN ('system','admin','none'));

UPDATE memory SET lock = 'system' WHERE protected = TRUE;

DROP INDEX ix_memory_protected_key;          -- WHERE protected — gone with the column
ALTER TABLE memory DROP COLUMN protected;

-- ---------------------------------------------------------------------------
-- 4. Backfill the new identity / lifecycle columns for every existing row.
--    Runs under the app.tenant_id GUC (TenantyMigrationCallback) so the rows
--    are visible through RLS — a missing GUC would make this a silent 0-row
--    no-op (the lying-green failure mode). version/is_head/state/existence
--    already carry their CE values via the column DEFAULTs above; logical_id +
--    is_private have no default and are set here.
--      - logical_id: a FRESH uuid PER ROW (gen_random_uuid() is volatile →
--        evaluated once per row, so every row gets a distinct identity, A1.3 (3)).
--      - is_private: denormalized from the row's scope kind (A1.3 (1a)).
-- ---------------------------------------------------------------------------
UPDATE memory m
   SET logical_id = gen_random_uuid(),
       is_private = (s.kind = 'private')
  FROM scope s
 WHERE s.id = m.scope_id;

-- ---------------------------------------------------------------------------
-- 5. Promote the backfilled columns to NOT NULL. These ALTERs are themselves
--    fail-loud guards: a NULL logical_id or is_private (e.g. a row whose
--    scope_id matched no scope — impossible under the FK, but asserted anyway)
--    aborts the migration rather than landing a half-built head.
-- ---------------------------------------------------------------------------
ALTER TABLE memory ALTER COLUMN logical_id SET NOT NULL;
ALTER TABLE memory ALTER COLUMN is_private SET NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. Collision guard (fail-loud, A1.3 (3a)). The old uq_memory_key was
--    per-owner — two different authors COULD hold the same key in a shared
--    scope (the C2 finding). The new shared index is author-independent, so any
--    such pre-existing pair must be resolved BY HAND before the index is built.
--    This block RAISES, naming the offending (scope_id, key) pairs. There is NO
--    auto-resolution (no "latest wins") — that is silent data loss and violates
--    the curation thesis (ADR-0024 Rejected-alternatives). Whether any
--    collisions exist in the data is the dispatch's measurement; the rule is
--    collision-safe regardless of the count. Empty data → no rows → no RAISE.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    collisions text;
BEGIN
    SELECT string_agg(
               format('(scope_id=%s, key=%L, live_heads=%s)', scope_id, key, cnt),
               '; ' ORDER BY scope_id, key)
      INTO collisions
      FROM (
          SELECT m.scope_id, m.key, count(*) AS cnt
            FROM memory m
           WHERE m.key IS NOT NULL
             AND NOT m.is_private
             AND m.is_head
             AND m.existence = 'active'
           GROUP BY m.scope_id, m.key
          HAVING count(*) > 1
      ) dup;

    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION
            'V16 backfill: shared-key collision(s) detected — manual resolution '
            'required before the author-independent unique index can be built '
            '(ADR-0024 Amendment 1 §A1.3 (3a), fail-loud, NO auto-resolution). '
            'Rename or merge the offending entries, then re-run: %', collisions
            USING ERRCODE = 'P0001';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Replace uq_memory_key with the two scope-kind-differentiated partial
--    unique indexes (A1.3 (1)). owner_subject LEAVES the shared tuple (it stays
--    on the row as head authorship) and STAYS in the private tuple. Keyless
--    rows (key IS NULL) never collide, as today. Built AFTER the collision
--    guard so a pre-existing duplicate fails the guard (with named pairs), not
--    the index build (with a cryptic unique-violation).
-- ---------------------------------------------------------------------------
DROP INDEX uq_memory_key;

-- shared (global / project): one canonical live head per key, author-independent.
CREATE UNIQUE INDEX uq_memory_shared_key
    ON memory (scope_id, key)
    WHERE is_head AND existence = 'active' AND NOT is_private AND key IS NOT NULL;

-- private: per author — Bernd's and Peter's private `ratification.process` coexist.
CREATE UNIQUE INDEX uq_memory_private_key
    ON memory (scope_id, owner_subject, key)
    WHERE is_head AND existence = 'active' AND is_private AND key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 8. content_relation — typed cross-logical_id relations (§8 / A1.4). Targets
--    are logical_ids (never keys, never row_ids); to_version NULL = track-head,
--    set = pinned. RLS-isolated like memory (V3 pattern). Backfill empty.
--    NO kumbuka_ops_reader grant (P1 — the reader gets no new content surface).
--    There is intentionally NO FK to memory(logical_id): logical_id is not
--    unique on memory (many versions share it in EE), and references survive
--    GDPR tombstones (§ Consequences) — so referential integrity is logical,
--    not a hard FK.
-- ---------------------------------------------------------------------------
CREATE TABLE content_relation (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    from_logical_id UUID         NOT NULL,
    to_logical_id   UUID         NOT NULL,
    to_version      INT,                       -- NULL = track-head, set = pinned (§8)
    kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('supersedes','refines','references')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_content_relation_from ON content_relation (tenant_id, from_logical_id);
CREATE INDEX ix_content_relation_to   ON content_relation (tenant_id, to_logical_id);

ALTER TABLE content_relation ENABLE ROW LEVEL SECURITY;
ALTER TABLE content_relation FORCE  ROW LEVEL SECURITY;
CREATE POLICY content_relation_tenant_isolation ON content_relation
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Acyclicity guard on `supersedes` (§8). Supersession-within a logical_id is
-- implicit; supersession-across logical_ids is this typed edge and must stay a
-- DAG. The guard rejects self-supersession and any edge that would close a
-- cycle (a recursive walk: can `to` already reach `from` via supersedes?).
-- `refines` / `references` are unconstrained. Structural, not code discipline.
CREATE OR REPLACE FUNCTION content_relation_acyclic_supersedes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'supersedes' THEN
        IF NEW.from_logical_id = NEW.to_logical_id THEN
            RAISE EXCEPTION
                'content_relation: supersedes cannot be self-referential (logical_id=%)',
                NEW.from_logical_id
                USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (
            WITH RECURSIVE reach(node) AS (
                SELECT NEW.to_logical_id
                UNION
                SELECT cr.to_logical_id
                  FROM content_relation cr
                  JOIN reach r ON cr.from_logical_id = r.node
                 WHERE cr.kind = 'supersedes'
            )
            SELECT 1 FROM reach WHERE node = NEW.from_logical_id
        ) THEN
            RAISE EXCEPTION
                'content_relation: supersedes edge %->% would create a cycle (§8 acyclicity guard)',
                NEW.from_logical_id, NEW.to_logical_id
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER content_relation_acyclicity
    BEFORE INSERT OR UPDATE ON content_relation
    FOR EACH ROW EXECUTE FUNCTION content_relation_acyclic_supersedes();

-- ---------------------------------------------------------------------------
-- 9. Extend the V12 protected trigger from DELETE-only to DELETE + UPDATE
--    (§13 BEFORE-UPDATE parity trigger; closes F3 /
--    open-question.protected-update-parity-trigger). The lock blocks
--    move / rename / delete (§13) — NOT content edit: the SYSTEM seeder
--    legitimately rewrites the content of a locked row through the SYSTEM
--    remember() path, so a blanket UPDATE-block would break re-seed. The UPDATE
--    branch therefore fires only when a LOCKED row's scope_id (move) or key
--    (rename) changes. The RAISE message keeps the marker "memory row is
--    protected" that ProtectedDeleteBlockDetector matches on (unchanged).
--    Function name kept (the DELETE trigger already references it); it now
--    also serves the UPDATE trigger via TG_OP.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION memory_block_protected_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.lock IN ('system','admin') THEN
            RAISE EXCEPTION
                'memory row is protected (row_id=% key=%) — locked rows are structurally undeletable (D-CORE-11 / ADR-0024 §13)',
                OLD.row_id, OLD.key
                USING ERRCODE = 'P0001';
        END IF;
        RETURN OLD;
    ELSE  -- UPDATE: block move (scope_id) / rename (key) of a locked row only.
        IF OLD.lock IN ('system','admin')
           AND (NEW.scope_id IS DISTINCT FROM OLD.scope_id
                OR NEW.key      IS DISTINCT FROM OLD.key) THEN
            RAISE EXCEPTION
                'memory row is protected (row_id=% key=%) — locked rows cannot be moved or renamed (ADR-0024 §13)',
                OLD.row_id, OLD.key
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
END;
$$;

CREATE TRIGGER memory_protected_update_block
    BEFORE UPDATE ON memory
    FOR EACH ROW
    EXECUTE FUNCTION memory_block_protected_delete();

-- ---------------------------------------------------------------------------
-- 10. scope.locked (FEAT-19, A1.5) — the §13 entry-lock primitive one level up
--     (read-only / frozen scope). Reserved now (not migratable later); NO
--     enforcement is built here (FEAT-19 carries that). The only scope-table
--     change in this migration.
-- ---------------------------------------------------------------------------
ALTER TABLE scope ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
