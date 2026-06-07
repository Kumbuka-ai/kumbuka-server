-- ===========================================================================
-- V2: data-model alignment with the handoff (§C) and the ratified decisions
--     D3 (settings + defaultScope fallback), D4 (scope slug as URL identity),
--     D5 (source channel for authorship).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. Scope: surrogate UUID id keeps the FK target, slug is the URL identity.
--    See ADR-0007.
-- ---------------------------------------------------------------------------
ALTER TABLE scope
    ADD COLUMN slug         TEXT,
    ADD COLUMN description  TEXT,
    ADD COLUMN fixed        BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN created_by   TEXT;

-- Backfill existing seeds (private + global) — name already matches the slug
-- convention. Mark only `global` as fixed (the singleton team-wide scope).
UPDATE scope SET slug = name, fixed = (kind = 'global') WHERE slug IS NULL;

ALTER TABLE scope ALTER COLUMN slug SET NOT NULL;
ALTER TABLE scope ADD CONSTRAINT scope_slug_kebab
    CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
ALTER TABLE scope ADD CONSTRAINT uq_scope_slug UNIQUE (tenant_id, slug);

-- ---------------------------------------------------------------------------
-- 2. Memory: source channel (server-derived per ADR-0008), key kebab/dot
--    validation. The `mcp` default is conservative: any forgotten setter
--    surfaces as MCP authorship, not as console.
-- ---------------------------------------------------------------------------
ALTER TABLE memory ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'mcp'
    CHECK (source IN ('console', 'mcp'));

ALTER TABLE memory ADD CONSTRAINT memory_key_format CHECK (
    key IS NULL OR key ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
);

-- ---------------------------------------------------------------------------
-- 3. UserAccount: status enum replaces the enabled bool; display_name +
--    last_seen_at for the team screen + account screen.
-- ---------------------------------------------------------------------------
ALTER TABLE user_account
    ADD COLUMN display_name  TEXT,
    ADD COLUMN status        VARCHAR(16),
    ADD COLUMN last_seen_at  TIMESTAMPTZ;

UPDATE user_account
   SET status = CASE WHEN enabled THEN 'active' ELSE 'disabled' END
 WHERE status IS NULL;

ALTER TABLE user_account ALTER COLUMN status SET NOT NULL;
ALTER TABLE user_account ADD CONSTRAINT user_status_check
    CHECK (status IN ('active', 'invited', 'disabled'));
ALTER TABLE user_account DROP COLUMN enabled;

-- ---------------------------------------------------------------------------
-- 4. team_settings: singleton-per-tenant. Drives writePolicy/defaultScope/
--    createScopes (handoff §D + D3).
-- ---------------------------------------------------------------------------
CREATE TABLE team_settings (
    tenant_id         UUID         PRIMARY KEY,
    write_policy      VARCHAR(16)  NOT NULL DEFAULT 'ask'
                       CHECK (write_policy IN ('ask', 'project', 'global')),
    default_scope_id  UUID         REFERENCES scope(id) ON DELETE SET NULL,
    create_scopes     VARCHAR(16)  NOT NULL DEFAULT 'admins'
                       CHECK (create_scopes IN ('admins', 'members')),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed the singleton row.
INSERT INTO team_settings (tenant_id)
    VALUES ('00000000-0000-0000-0000-000000000001');
