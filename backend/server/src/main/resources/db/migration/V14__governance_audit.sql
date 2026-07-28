-- ===========================================================================
-- V14 — D-OPS-16 / D-CORE-9: tenant-side governance audit (member.erase).
--
-- The team-admin erasure path (D-OPS-16 rev., the PRIMARY path) must be
-- audited as a shared-governance event under the TEAM-ADMIN actor — distinct
-- from the provider's `ops.provider_audit` (operator actor, provider plane).
-- kumbuka-server had no audit surface; this is the minimal append-only row
-- store for it. The D-CORE-9 admin-facing audit-log VIEW stays post-beta EE
-- and will read this table when built — this migration is the data substrate,
-- not that feature.
--
-- Append-only is enforced with a TRIGGER, not a GRANT/RLS REVOKE, mirroring
-- the V12 protected-delete rationale: a trigger holds against EVERY caller —
-- member, admin, service account, the runtime role kumbuka (LOGIN, not
-- BYPASSRLS), and the BYPASSRLS provider reader kumbuka_ops_reader — whereas
-- RLS/GRANT shapes are skipped by the latter. Tenant
-- isolation still gets the standard RLS layer (ADR-0011, mirrors V3).
-- ===========================================================================

CREATE TABLE governance_audit (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    -- KC `sub` of the admin who performed the action (never an email — the
    -- admin tenant's principal-claim is pinned to `sub`, D-CORE-12).
    actor_subject   TEXT        NOT NULL,
    action          TEXT        NOT NULL,
    -- KC `sub` of the affected member (e.g. the erased subject). Nullable for
    -- actions that aren't member-targeted.
    target_subject  TEXT,
    -- JSONB outcome payload (counts, per-step flags, email). NEVER memory
    -- content — erasure returns counts only.
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- --- tenant isolation (RLS layer 2, mirrors V3) --------------------------
ALTER TABLE governance_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE governance_audit FORCE  ROW LEVEL SECURITY;
CREATE POLICY governance_audit_tenant_isolation ON governance_audit
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- append-only (trigger; holds even against BYPASSRLS, see V12) ---------
CREATE OR REPLACE FUNCTION governance_audit_block_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'governance_audit is append-only (D-CORE-9) — % is not permitted', TG_OP
        USING ERRCODE = 'P0001';
END;
$$;

CREATE TRIGGER governance_audit_no_update
    BEFORE UPDATE ON governance_audit
    FOR EACH ROW EXECUTE FUNCTION governance_audit_block_mutation();

CREATE TRIGGER governance_audit_no_delete
    BEFORE DELETE ON governance_audit
    FOR EACH ROW EXECUTE FUNCTION governance_audit_block_mutation();

-- --- indexes -------------------------------------------------------------
CREATE INDEX ix_governance_audit_tenant_time
    ON governance_audit (tenant_id, created_at DESC);
CREATE INDEX ix_governance_audit_target
    ON governance_audit (tenant_id, target_subject);
