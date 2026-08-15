-- ===========================================================================
-- V21: platform tenancy directory — the published read contract.
--
-- Realises D-STEER-13 (platform-specs), ADR-0038 §5, §11.
--
-- The two steering services (worklist manager, logbook) must answer, on every
-- verb, whether a scope exists and whether the calling subject may enter it —
-- without holding any privilege on the memory engine's base tables. This
-- migration publishes that answer as ONE view over a new shared schema and
-- creates the two consuming roles with SELECT on the view and nothing else.
--
--   * schema `platform`                    — owns the surface, not the content.
--   * view `platform.scope_access`         — question-shaped: the access answer
--       (scope_id, tenant_id, slug, archived)  for the caller, never the
--                                            membership that produces it.
--   * roles kumbuka_worklist, kumbuka_logbook — LOGIN, explicitly NOT BYPASSRLS.
--
-- Security model (D-STEER-13 §6 — the view-owner risk):
--   The view carries NO `security_invoker`, so it reads its base tables with
--   the VIEW OWNER's privileges; the two consuming roles therefore need no
--   privilege on team / scope / user_account. This narrowing holds ONLY IF the
--   view is owned by the base-table owner (kumbuka: LOGIN, non-super,
--   non-BYPASSRLS) so that FORCE ROW LEVEL SECURITY still binds it. A superuser-
--   or BYPASSRLS-owned view evaporates the tenant filter silently — rows
--   returned, no error, every test green. Because the migrator is the `postgres`
--   superuser, this view is created superuser-owned and MUST be normalised to
--   owner `kumbuka` by the deployment's owner-normalisation step (exactly as
--   every base table is). Criterion 7 — rolsuper=false AND rolbypassrls=false
--   for pg_views.viewowner of platform.scope_access — is the guard, and it needs
--   a witnessed red probe. Do NOT add `security_invoker` and do NOT pin the owner
--   inside this migration; both were rejected (D-STEER-13; ADR-0026 Variant 2a).
--
-- Placement: the memory engine's COMMUNITY Flyway chain (ADR-0033) — a
-- self-hoster must be able to run the steering services. Additive and
-- N-1-compatible: a new schema, a new view, two roles, two grants; no existing
-- object is altered.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. schema platform — the published surface. Never reachable via PUBLIC.
-- ---------------------------------------------------------------------------
CREATE SCHEMA platform;
REVOKE ALL ON SCHEMA platform FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- 2. the two steering roles — LOGIN, and explicitly NOT BYPASSRLS.
--    Shape mirrors V6's kumbuka_ops_reader, minus BYPASSRLS. The placeholder
--    passwords are replaced via `ALTER ROLE … PASSWORD …` in the production
--    init script; CI / DevServices ignore these blocks when the role exists.
-- ---------------------------------------------------------------------------
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_worklist') THEN
        CREATE ROLE kumbuka_worklist LOGIN PASSWORD 'change-me-kumbuka-worklist';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_logbook') THEN
        CREATE ROLE kumbuka_logbook  LOGIN PASSWORD 'change-me-kumbuka-logbook';
    END IF;
END
$do$;

-- ---------------------------------------------------------------------------
-- 3. the published read contract.
--
--    One row per project scope the calling subject may enter. Two session
--    settings, both read with the fail-closed idiom V3 already uses, so an
--    unset value yields NULL yields no rows:
--        app.tenant_id  — the bound tenant
--        app.subject    — the calling subject (NEW; set by the consumers)
--    The lookup IS the membership check: a subject sees a project scope only
--    when it is an enabled member (user_account.enabled) of that scope's tenant.
--    `archived` is PUBLISHED, never filtered — a write into a retired scope must
--    be refusable with a specific error, not with "not found". The private and
--    global scopes never appear (kind = 'project').
-- ---------------------------------------------------------------------------
CREATE VIEW platform.scope_access AS
    SELECT s.id        AS scope_id,
           s.tenant_id AS tenant_id,
           s.slug      AS slug,
           s.archived  AS archived
    FROM scope s
    JOIN user_account ua ON ua.tenant_id = s.tenant_id
    WHERE s.kind = 'project'
      AND s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
      AND ua.subject  = NULLIF(current_setting('app.subject',   true), '')
      AND ua.enabled;

-- ---------------------------------------------------------------------------
-- 4. grants — individual, never ON ALL TABLES, never to PUBLIC.
--    kumbuka_ops_reader receives NOTHING here (ADR-0038 §3, ADR-0014):
--    no USAGE on the schema, no SELECT on the view.
-- ---------------------------------------------------------------------------
GRANT USAGE  ON SCHEMA platform     TO kumbuka_worklist;
GRANT USAGE  ON SCHEMA platform     TO kumbuka_logbook;
GRANT SELECT ON platform.scope_access TO kumbuka_worklist;
GRANT SELECT ON platform.scope_access TO kumbuka_logbook;
