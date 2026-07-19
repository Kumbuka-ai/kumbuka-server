-- ===========================================================================
-- V19: tenant_limits — per-tenant write-rate limit overrides.
--
-- One row per tenant that has an OPERATOR-SET override for the write-rate
-- token bucket; ABSENCE of a row means the deployment-wide defaults apply
-- (kumbuka.rate-limit.default-* in application.properties). Two bands:
--
--   * write_*         — the per-principal write bucket (keyed by the token
--                       subject). Overrides the default band for every
--                       member of this tenant.
--   * tenant_write_*  — an OPTIONAL tenant-aggregate bucket across all of
--                       the tenant's members. NULL = no aggregate limit is
--                       applied (the shipped default). Setting these values
--                       activates the aggregate bucket at runtime — a config
--                       change, not a code change.
--
-- This table holds CONFIGURATION ONLY. Bucket fill state is ephemeral
-- in-process limiter state and is deliberately never persisted — there is no
-- throttle-event table and no per-member counter anywhere in this schema,
-- and none may be added: rate limiting must not become activity monitoring.
--
-- Purely additive: no existing table, column, or constraint is touched, so
-- the previous application image runs unchanged against this schema
-- (backward-compatible by construction).
--
-- Row-level security: enabled + forced like every tenant-keyed table, but
-- with a deliberately OPEN read policy. The limiter evaluates BEFORE the
-- per-request tenant binding exists (the whole point is to throttle before
-- any tenant resolution work), so its config read runs without the
-- app.tenant_id GUC — the same chicken-and-egg the team-alias routing
-- lookup has. These rows are operator-set limit numbers, never member
-- content, so an open SELECT leaks nothing; writes stay tenant-bound so no
-- app code path can create or mutate another tenant's override without the
-- GUC bound to that tenant.
-- ===========================================================================

CREATE TABLE tenant_limits (
    tenant_id                           UUID         PRIMARY KEY,
    -- Per-principal write bucket override (all three set, or all three NULL).
    write_burst_capacity                INTEGER      CHECK (write_burst_capacity > 0),
    write_refill_tokens                 INTEGER      CHECK (write_refill_tokens > 0),
    write_refill_period_seconds         INTEGER      CHECK (write_refill_period_seconds BETWEEN 1 AND 86400),
    -- Tenant-aggregate write bucket (optional; NULL = inactive).
    tenant_write_burst_capacity         INTEGER      CHECK (tenant_write_burst_capacity > 0),
    tenant_write_refill_tokens          INTEGER      CHECK (tenant_write_refill_tokens > 0),
    tenant_write_refill_period_seconds  INTEGER      CHECK (tenant_write_refill_period_seconds BETWEEN 1 AND 86400),
    updated_at                          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Each band is meaningful only as a complete (burst, refill, period)
    -- triple: all set or all NULL.
    CONSTRAINT tenant_limits_write_band_complete CHECK (
        (write_burst_capacity IS NULL) = (write_refill_tokens IS NULL)
        AND (write_refill_tokens IS NULL) = (write_refill_period_seconds IS NULL)
    ),
    CONSTRAINT tenant_limits_tenant_band_complete CHECK (
        (tenant_write_burst_capacity IS NULL) = (tenant_write_refill_tokens IS NULL)
        AND (tenant_write_refill_tokens IS NULL) = (tenant_write_refill_period_seconds IS NULL)
    ),
    -- No ghost rows: a row with neither band set carries no information —
    -- clearing the last override deletes the row.
    CONSTRAINT tenant_limits_not_empty CHECK (
        write_burst_capacity IS NOT NULL OR tenant_write_burst_capacity IS NOT NULL
    )
);

ALTER TABLE tenant_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_limits FORCE  ROW LEVEL SECURITY;

-- Reads are open (see header: the limiter reads config BEFORE the tenant
-- binding exists; these are limit numbers, not member content).
CREATE POLICY tenant_limits_read ON tenant_limits
    FOR SELECT
    USING (true);

-- Writes stay tenant-bound like every other tenant-keyed table.
CREATE POLICY tenant_limits_insert ON tenant_limits
    FOR INSERT
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_limits_update ON tenant_limits
    FOR UPDATE
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_limits_delete ON tenant_limits
    FOR DELETE
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
