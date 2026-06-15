-- ===========================================================================
-- V13 — D-CORE-12: backfill owner_subject + scope.created_by from email to
-- Keycloak `sub` (UUID).
--
-- Pre-D-CORE-12, the SaaS bearer-token path stamped `preferred_username`
-- (the email/login) into `memory.owner_subject` and `scope.created_by`,
-- because saas-runtime never pinned `quarkus.oidc.mcp.principal-claim=sub`
-- and Quarkus' service-tenant default falls through to preferred_username.
-- The operator erasure flow looks up by KC UUID and OSS-side
-- `MemberErasureService.eraseSubject` does strict equality on owner_subject
-- → no match, no rows erased, silent no-op.
--
-- This migration restores the contract: rewrite every email-shaped
-- authorship to the matching KC UUID via the `user_account` table (which
-- already pairs (tenant_id, subject UUID, email)). Sentinels (anything
-- starting with `__`, currently `__former-member__` and `__system__`)
-- are skipped — they're not real principals.
--
-- Volume: pre-beta data lives in dogfood + test tenants; the inline
-- migration is the right shape (no need to defer to a keystone pass).
-- A guard at the end asserts no email-shaped rows remain in either column.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. Memory rows: rewrite owner_subject from email → sub.
-- ---------------------------------------------------------------------------
UPDATE memory m
   SET owner_subject = ua.subject
  FROM user_account ua
 WHERE ua.tenant_id = m.tenant_id
   AND ua.email = m.owner_subject
   -- Skip server-derived sentinels (__system__, __former-member__).
   AND m.owner_subject NOT LIKE '\_\_%' ESCAPE '\'
   -- Skip already-UUID-shaped values (defence-in-depth — re-runs are no-ops).
   AND m.owner_subject !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- ---------------------------------------------------------------------------
-- 2. Scope.created_by: same rewrite, same source-of-truth join.
--    Some rows have NULL created_by (V1 scopes seeded before V2 added the
--    column); the join naturally skips those.
-- ---------------------------------------------------------------------------
UPDATE scope s
   SET created_by = ua.subject
  FROM user_account ua
 WHERE ua.tenant_id = s.tenant_id
   AND ua.email = s.created_by
   AND s.created_by NOT LIKE '\_\_%' ESCAPE '\'
   AND s.created_by !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- ---------------------------------------------------------------------------
-- 3. Guard: no email-shaped authorship may remain. Anything that escapes
--    the join above is either an orphan (user_account row missing) or a
--    value the join didn't match — both are bugs we want to see, not skip.
--    Sentinels (`__…`) are still allowed; UUIDs and sentinels are the only
--    valid values post-D-CORE-12.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    bad_memory  integer;
    bad_scope   integer;
BEGIN
    SELECT count(*) INTO bad_memory
      FROM memory
     WHERE owner_subject NOT LIKE '\_\_%' ESCAPE '\'
       AND owner_subject !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

    SELECT count(*) INTO bad_scope
      FROM scope
     WHERE created_by IS NOT NULL
       AND created_by NOT LIKE '\_\_%' ESCAPE '\'
       AND created_by !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

    IF bad_memory > 0 OR bad_scope > 0 THEN
        RAISE EXCEPTION
            'V13 backfill incomplete (D-CORE-12): % memory.owner_subject + % scope.created_by rows still email-shaped. '
            'These are members whose user_account row was missing or whose principal never matched any (tenant_id, email) pair. '
            'Investigate before unblocking the migration.',
            bad_memory, bad_scope;
    END IF;
END $$;
