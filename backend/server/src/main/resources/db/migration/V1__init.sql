-- ===========================================================================
-- V1: initial schema
--   Tables: team, user_account, scope, memory
--   Invariants enforced at the data layer (see ADR-0003).
--   Forward-compat: every user-data table carries tenant_id (ADR-0005).
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- team — singleton row in this edition (tenant_id == id).
-- ---------------------------------------------------------------------------
CREATE TABLE team (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    name        TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- user_account — mirror of Keycloak user (subject = `sub` claim).
-- ---------------------------------------------------------------------------
CREATE TABLE user_account (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    subject     TEXT         NOT NULL,
    email       TEXT         NOT NULL,
    role        VARCHAR(16)  NOT NULL CHECK (role IN ('member','admin')),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_subject UNIQUE (tenant_id, subject),
    CONSTRAINT uq_user_email   UNIQUE (tenant_id, email)
);

-- ---------------------------------------------------------------------------
-- scope — registry of scopes. Exactly one 'private' row and one 'global' row
-- per tenant; many 'project' rows.
-- ---------------------------------------------------------------------------
CREATE TABLE scope (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    name        TEXT         NOT NULL,
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN ('private','project','global')),
    archived    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_scope_name UNIQUE (tenant_id, name)
);

-- Exactly one 'private' and one 'global' scope per tenant.
CREATE UNIQUE INDEX uq_scope_one_private ON scope (tenant_id) WHERE kind = 'private';
CREATE UNIQUE INDEX uq_scope_one_global  ON scope (tenant_id) WHERE kind = 'global';

-- ---------------------------------------------------------------------------
-- memory — the actual content.
--   owner_subject is always set (even for shared scopes: records the author).
--   For private rows, ownership = visibility. See ADR-0003.
-- ---------------------------------------------------------------------------
CREATE TABLE memory (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL,
    owner_subject  TEXT         NOT NULL,
    scope_id       UUID         NOT NULL REFERENCES scope(id) ON DELETE RESTRICT,
    type           VARCHAR(32)  NOT NULL CHECK (type IN ('decision','convention','constraint','open_question','glossary','status')),
    key            TEXT,
    content        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_memory_tenant_scope ON memory (tenant_id, scope_id);
CREATE INDEX idx_memory_owner        ON memory (tenant_id, owner_subject, scope_id);

-- Upsert target: (scope, owner, key). Keys are optional; only enforced when present.
-- For shared scopes this means each author has their own keyspace; for private
-- it gives the natural per-user upsert. Revisit if team semantics demand
-- a single team-wide key registry per scope.
CREATE UNIQUE INDEX uq_memory_key ON memory (tenant_id, scope_id, owner_subject, key)
    WHERE key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Seed: singleton team + system scopes ('private', 'global').
-- The singleton team's id is fixed so the application can reference it
-- via a constant (memory.tenant-id in application.properties).
-- ---------------------------------------------------------------------------
INSERT INTO team (id, tenant_id, name) VALUES
    ('00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     'Team');

INSERT INTO scope (tenant_id, name, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', 'private', 'private'),
    ('00000000-0000-0000-0000-000000000001', 'global',  'global');
