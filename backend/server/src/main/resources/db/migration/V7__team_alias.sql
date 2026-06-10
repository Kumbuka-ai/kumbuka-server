-- ===========================================================================
-- V7: team.alias — the canonical per-tenant routing key.
--
-- D-OPS-24 (platform-specs).  The tenant subdomain ("acme" for
-- "acme.kumbuka.ai") is the single key the SaaS resolver uses to look up
-- the data tenant_id; it must be a stable application-level value, not a
-- derived slug of the display name.  Single-tenant CE installs carry the
-- column too — they use alias = 'default' for the seed row.
--
-- The DB enforces SHAPE (regex + length + IDN-prefix ban) and UNIQUENESS.
-- The RESERVED-ALIAS LIST (console/ops/auth/mcp/memory/api/www, plus the
-- broader security/identity/edge set) is enforced application-side in
-- ops-console's TenantProvisioningService, so the OSS CE install does not
-- need to be patched whenever the SaaS reserved set changes.  'default' is
-- shape-allowed by an explicit carve-out so this migration can backfill
-- the CE seed row without tripping its own CHECK.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Add the column nullable, backfill the CE seed row, then tighten.
-- ---------------------------------------------------------------------------
ALTER TABLE team ADD COLUMN alias TEXT;

UPDATE team SET alias = 'default' WHERE alias IS NULL;

ALTER TABLE team ALTER COLUMN alias SET NOT NULL;

-- Two tenants must not share an alias — this is the routing key.
ALTER TABLE team ADD CONSTRAINT team_alias_unique UNIQUE (alias);

-- Shape: lowercase a-z 0-9 and ASCII hyphens, must start and end with
-- alnum, length 3..32.  The carve-out for 'default' keeps the seed row
-- valid even though a 7-char ASCII alias is otherwise legal.
--
-- IDN ban (xn--…): a punycode-prefixed alias decodes in the browser into
-- a Unicode hostname that can visually impersonate other labels
-- ("console" vs a Cyrillic look-alike).  Block at the DB so no code path
-- can persist one, even if the application list of reserved aliases
-- drifts.
ALTER TABLE team ADD CONSTRAINT team_alias_format CHECK (
    alias = 'default' OR (
        alias ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'
        AND alias NOT LIKE 'xn--%'
    )
);
