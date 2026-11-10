-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V26: the tag vocabulary — one flat list of declared tokens per scope, and
--      the read contract the services see it through.
--
-- A tag belongs to exactly one scope. It is DECLARED before anything may use
-- it, and it can be WITHDRAWN but never deleted: a withdrawn tag keeps its row,
-- its token stays taken, and the services refuse new assignments to it while
-- existing ones stand. Facets are a naming convention inside the token
-- (`component:api`), not a structure. Assigning a tag to an object is each
-- service's own business and lives in that service's schema, not here.
--
--   * table `platform.tag`        — the vocabulary itself. The core writes it;
--                                   no service holds any privilege on it.
--   * view  `platform.tag_access` — the ONLY read contract the services have on
--                                   the vocabulary, the way `scope_access` is
--                                   their only read contract on the scopes.
--
-- WHERE THE VIEW'S VISIBILITY COMES FROM. The view carries no
-- `security_invoker`, so it reads `platform.tag` with its OWNER's privileges,
-- exactly like `scope_access`. Its rows are therefore narrowed by the join on
-- `platform.scope_access`, which carries the tenant predicate and the
-- subject's visibility rules in ITS OWN definition: a tag appears only when its
-- scope is visible to the caller named by `app.tenant_id` and `app.subject`. The
-- tenant policy and FORCE ROW LEVEL SECURITY on `platform.tag` are the second
-- layer under that join — they bind the view's owner as long as the owner is
-- neither a superuser nor BYPASSRLS, which is why the owner follows the same
-- rule as for `scope_access`: the migrator creates the view, and the
-- deployment's owner normalisation hands it to the non-privileged base-table
-- owner together with every base table. Ownership is not pinned here.
--
-- `created_by` is who declared the tag — a who-did-what field. Nothing in this
-- schema counts declarations or uses, per tag or per member, and nothing may.
--
-- Additive and compatible with the previous image: a new table, a new view and
-- one grant. No existing object is altered, `platform.scope_access` included.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. the vocabulary.
--
-- `scope_id` cascades on delete because a scope row is only ever removed by
-- the tenant purge, which removes the tenant's tags with it; a scope is
-- otherwise archived, never deleted. A single tag is never deleted at all.
-- ---------------------------------------------------------------------------
CREATE TABLE platform.tag (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL,
    scope_id     uuid        NOT NULL REFERENCES platform.scope(id) ON DELETE CASCADE,
    token        text        NOT NULL
                 CONSTRAINT tag_token_format CHECK (token ~ '^[a-z0-9][a-z0-9:-]{0,62}$'),
    description  text        NULL
                 CONSTRAINT tag_description_length CHECK (char_length(description) <= 500),
    status       text        NOT NULL DEFAULT 'declared'
                 CONSTRAINT tag_status_values CHECK (status IN ('declared', 'withdrawn')),
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    withdrawn_at timestamptz NULL,
    CONSTRAINT uq_tag_scope_token UNIQUE (scope_id, token)
);

-- ---------------------------------------------------------------------------
-- 2. the tenant axis — the same predicate every other platform table carries,
--    with the same fail-closed reading of an unset `app.tenant_id`.
-- ---------------------------------------------------------------------------
ALTER TABLE platform.tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY tag_tenant_isolation ON platform.tag
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 3. the read contract.
--
-- One row per tag of a scope the caller may see. `status` is PUBLISHED, never
-- filtered: a service must be able to refuse a new assignment to a withdrawn
-- tag by name, not with "not found", and must keep resolving the tags its
-- existing assignments point at.
-- ---------------------------------------------------------------------------
CREATE VIEW platform.tag_access AS
    SELECT t.id          AS tag_id,
           t.scope_id    AS scope_id,
           t.token       AS token,
           t.description AS description,
           t.status      AS status
    FROM platform.tag t
    JOIN platform.scope_access sa ON sa.scope_id = t.scope_id;

-- ---------------------------------------------------------------------------
-- 4. the grant — one statement, the three services that read `scope_access`,
--    nothing on the table. The services already hold USAGE on the schema.
-- ---------------------------------------------------------------------------
GRANT SELECT ON platform.tag_access
    TO kumbuka_worklist, kumbuka_dispatch, kumbuka_memory;
