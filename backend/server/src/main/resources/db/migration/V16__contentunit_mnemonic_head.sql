-- ===========================================================================
-- V16 — ContentUnit keystone, Step 1 (CE / Mnemonic head). ADR-0024 + Amend. 1–4.
--
-- Pre-beta, NON-migratable identity/versioning rebuild of the `memory` table.
-- Realises only the Mnemonic head and only what ADR-0024 §4 marks as living in
-- both tiers. CE is an honest update-in-place (UiP) system (Amendment 4): an
-- edit mutates the head in place, no history; EE turns the same shape into
-- Copy-on-Write. No history/append-only/rollback/Section/Document/Blob here.
--
-- Identity (Amendment 3): there is NO surrogate row_id. The primary key is the
-- composite (logical_id, version) from birth — eineindeutig in both tiers (CE
-- version is always 1; EE appends higher versions against an unchanged PK). The
-- old V1 `memory.id` is dropped entirely (nothing FK-references a physical row:
-- §8 references target logical_id; scope_stats→scope(id); governance_audit holds
-- TEXT subjects).
--
-- DDL is RLS-exempt; the backfill DML runs under the `app.tenant_id` GUC set by
-- TenantyMigrationCallback (BEFORE_EACH_MIGRATE) so it is visible through the V3
-- RLS policies. Ordering (called out inline):
--   1. add the new head columns (logical_id / is_private nullable first)
--   2. add `lock`, backfill it from `protected`, then drop `protected`
--   3. backfill logical_id / is_private; enforce NOT NULL
--   4. drop the old `id` column + PK; make (logical_id, version) the PRIMARY KEY
--   5. fail-loud collision guard (shared-key duplicates) BEFORE the index build
--   6. replace uq_memory_key with the two scope-kind-differentiated indexes
--   7. content_relation table (+ acyclicity guard)
--   8. revert the protected trigger to DELETE-only (Amendment 2: NO UPDATE trigger)
--   9. scope.locked (FEAT-19, reserved)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. New ContentUnit head columns (A1.4, amended). logical_id + is_private are
--    NOT NULL in the final shape but have NO default (set in backfill), so they
--    are added nullable here and promoted to NOT NULL in step 3.
--    updated_by / updated_source (Amendment 4) are nullable head provenance for
--    the LAST in-place edit (NULL until first edit; backfill leaves them NULL).
-- ---------------------------------------------------------------------------
ALTER TABLE memory
    ADD COLUMN logical_id     UUID,                                   -- entry identity across versions (set in backfill)
    ADD COLUMN version        INT         NOT NULL DEFAULT 1,         -- (logical_id, version) PK coordinate + CE optimistic-lock counter (§A1.6)
    ADD COLUMN is_head        BOOLEAN     NOT NULL DEFAULT TRUE,      -- trivially TRUE in CE (no history); shape-parity with EE (§4)
    ADD COLUMN state          VARCHAR(16) NOT NULL DEFAULT 'published'
        CHECK (state IN ('draft','proposed','published','superseded')),  -- lifecycle (§7)
    ADD COLUMN existence      VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (existence IN ('active','deleted')),                   -- tombstone axis (§7)
    ADD COLUMN is_private     BOOLEAN,                                -- denormalized scope.kind='private', invariant (A1.3 (1a)); set in backfill
    ADD COLUMN valid_from     TIMESTAMPTZ,                            -- reserved, NOT enforced in CE (§6)
    ADD COLUMN valid_until    TIMESTAMPTZ,                            -- reserved, NOT enforced in CE (§6)
    ADD COLUMN updated_by     TEXT,                                  -- last-editor subject (Amendment 4); NULL until first edit
    ADD COLUMN updated_source VARCHAR(16)
        CHECK (updated_source IN ('console','mcp','system'));        -- last-edit channel (Amendment 4); NULL until first edit

-- ---------------------------------------------------------------------------
-- 2. `lock` replaces `protected` (§13). Add it (default 'none'), backfill from
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
-- 3. Backfill the new identity columns for every existing row. Runs under the
--    app.tenant_id GUC (TenantyMigrationCallback) so the rows are visible
--    through RLS — a missing GUC would make this a silent 0-row no-op (the
--    lying-green failure mode). version/is_head/state/existence already carry
--    their CE values via the column DEFAULTs; updated_by/updated_source stay
--    NULL (existing rows were never edited post-create, Amendment 4).
--      - logical_id: a FRESH uuid PER ROW (gen_random_uuid() is volatile →
--        evaluated once per row, so every row gets a distinct identity, A1.3 (3)).
--      - is_private: denormalized from the row's scope kind (A1.3 (1a)).
-- ---------------------------------------------------------------------------
UPDATE memory m
   SET logical_id = gen_random_uuid(),
       is_private = (s.kind = 'private')
  FROM scope s
 WHERE s.id = m.scope_id;

-- These ALTERs double as fail-loud guards: a NULL logical_id or is_private
-- (e.g. a row whose scope_id matched no scope — impossible under the FK) aborts
-- the migration rather than landing a half-built head.
ALTER TABLE memory ALTER COLUMN logical_id SET NOT NULL;
ALTER TABLE memory ALTER COLUMN is_private SET NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. Identity rebuild (Amendment 3): drop the old surrogate `id` (and its PK),
--    make (logical_id, version) the PRIMARY KEY directly. Dropping the column
--    cascades away the old single-column PK. (logical_id, version) is already
--    eineindeutig (fresh logical_id per row, version=1), so it needs no separate
--    UNIQUE — it IS the PK.
-- ---------------------------------------------------------------------------
ALTER TABLE memory DROP COLUMN id;
ALTER TABLE memory ADD CONSTRAINT memory_pkey PRIMARY KEY (logical_id, version);

-- ---------------------------------------------------------------------------
-- 5. Collision guard (fail-loud, A1.3 (3a)). The old uq_memory_key was
--    per-owner — two different authors COULD hold the same key in a shared
--    scope (the C2 finding). The new shared index is author-independent, so any
--    such pre-existing pair must be resolved BY HAND before the index is built.
--    This block RAISES, naming the offending (scope_id, key) pairs. There is NO
--    auto-resolution (no "latest wins"). Empty data → no rows → no RAISE.
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
-- 6. Replace uq_memory_key with the two scope-kind-differentiated partial
--    unique indexes (A1.3 (1)). owner_subject LEAVES the shared tuple (it stays
--    on the row as first-author authorship) and STAYS in the private tuple.
--    Keyless rows (key IS NULL) never collide, as today. Built AFTER the
--    collision guard so a pre-existing duplicate fails the guard (with named
--    pairs), not the index build (with a cryptic unique-violation).
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
-- 7. content_relation — typed cross-logical_id relations (§8 / A1.4). Targets
--    are logical_ids (never keys); to_version NULL = track-head, set = pinned.
--    RLS-isolated like memory (V3 pattern). Backfill empty. NO kumbuka_ops_reader
--    grant (P1). NO hard FK to memory(logical_id): logical_id is non-unique
--    across versions in EE, and references survive GDPR tombstones (§ Consequences).
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

-- Acyclicity guard on `supersedes` (§8): reject self-supersession and any edge
-- that would close a cycle (recursive walk: can `to` already reach `from`?).
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
-- 8. Protected trigger stays DELETE-only (Amendment 2 — NO UPDATE trigger).
--    The only legitimate in-place UPDATE of a locked row is the SYSTEM re-seed,
--    which an UPDATE trigger would wrongly block; the customer cannot reach an
--    in-place UPDATE of a locked row (application-layer guards: MCP
--    assertNoProtectedConflict, console SharedMemoryRepository.update). Locked-
--    entry integrity is the detective-corrective FEAT-21 validation job. DELETE
--    stays structurally blocked (D-CORE-11) — delete is never a legitimate SYSTEM
--    op on a seed. The function is re-pointed from the dropped `protected`/`id`
--    columns to `lock`/`logical_id`; the marker "memory row is protected" stays
--    (ProtectedDeleteBlockDetector matches it). NO `memory_protected_update_block`.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION memory_block_protected_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lock IN ('system','admin') THEN
        RAISE EXCEPTION
            'memory row is protected (logical_id=% key=%) — locked rows are structurally undeletable (D-CORE-11 / ADR-0024 §13)',
            OLD.logical_id, OLD.key
            USING ERRCODE = 'P0001';
    END IF;
    RETURN OLD;
END;
$$;
-- (The V12 BEFORE DELETE trigger `memory_protected_delete_block` already binds
--  this function; it is retained unchanged.)

-- ---------------------------------------------------------------------------
-- 9. scope.locked (FEAT-19, A1.5) — the §13 entry-lock primitive one level up
--    (read-only / frozen scope). Reserved now (not migratable later); NO
--    enforcement is built here (FEAT-19 carries that).
-- ---------------------------------------------------------------------------
ALTER TABLE scope ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
