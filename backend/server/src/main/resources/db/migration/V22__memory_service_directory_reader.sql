-- ===========================================================================
-- V22: the memory service joins the readers of the tenancy directory.
--
-- The directory view `platform.scope_access` (V21) answers one question for a
-- consuming service: does this scope exist, and may the calling subject enter
-- it. Its readers are ENUMERATED — each one named, each one granted
-- individually, never `ON ALL TABLES` and never to PUBLIC. Enumerating them is
-- this service's job, because this service is the one that owns the anchor and
-- therefore the one that decides who may ask it questions.
--
-- The memory engine is moving out into a service of its own, with its own
-- schema, its own database role and its own Flyway chain. Once it is out, the
-- scope it stores on every entry is no longer resolvable by a join — it is
-- another service's object, and a hard foreign key across that boundary is
-- exactly what the architecture forbids. So the reference becomes a runtime
-- read of this view, and this migration is the half of that arrangement which
-- belongs on this side of the line.
--
-- WHY THE ROLE IS CREATED HERE AS WELL AS THERE
--
-- A grant needs a grantee that exists. The role is created by the memory
-- service's own second migration, but the two chains run independently and in
-- either order — a deployment may well apply this one first, against a
-- database that has never seen the other service. So the block below creates
-- the role when it is absent, exactly as V21 does for the two steering
-- services, and does nothing when it is already there. Both definitions carry
-- the same placeholder password and the same attributes, so whichever runs
-- first produces the same role.
--
-- The role is LOGIN and explicitly NOT BYPASSRLS: it reads this view under the
-- session settings the view keys on, and a role that bypassed row-level
-- security would read the base tables' rows for every tenant at once, through
-- a view that would raise nothing while it happened.
--
-- WHAT IS DELIBERATELY NOT HERE
--
-- Nothing is granted to `kumbuka_ops_reader` and nothing to the operator: no
-- USAGE on this schema, no SELECT on this view. The provider's no-content
-- boundary is that absence.
--
-- No memory table is altered, dropped or prepared for removal. The existing
-- `memory` and `content_relation` tables in this schema stay in service and
-- untouched; this migration puts something beside them and takes nothing away.
-- ===========================================================================

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_memory') THEN
        CREATE ROLE kumbuka_memory LOGIN PASSWORD 'change-me-kumbuka-memory';
    END IF;
END
$do$;

-- Individual grants, on the schema and on the one view. USAGE on the schema is
-- not a privilege on anything inside it; the SELECT below is the whole of the
-- entitlement, and there is deliberately no INSERT, UPDATE or DELETE — the
-- contract is a question the consumer asks, never one it answers.
GRANT USAGE  ON SCHEMA platform       TO kumbuka_memory;
GRANT SELECT ON platform.scope_access TO kumbuka_memory;
