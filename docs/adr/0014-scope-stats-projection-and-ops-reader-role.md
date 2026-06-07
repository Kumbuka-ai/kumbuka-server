# ADR-0014: `scope_stats` projection + `kumbuka_ops_reader` DB role

- Status: Accepted
- Date: 2026-06-07
- Builds on: [ADR-0011](0011-multitenancy-seam.md), [ADR-0013](0013-spi-as-separate-maven-module.md)
- Driven by: the commercial **ops-console** repo (D-OPS-4 + D-OPS-5)
  needs **counts** for the tenant directory and tenant-detail screens
  without **ever** reading memory content — even at the SQL level.

## Context

The team-facing OSS console reads memory through its own session
context — RLS-isolated, owner-aware. The commercial ops-console works
above tenants and must show "this tenant has 47 entries in `global`,
12 in `atlas-web`, 0 in private (its container exists but the count
isn't visible)" without any code path that could read memory rows.

Two questions this ADR answers:

1. Where do the counts come from?
2. How do we make it **structurally** impossible for the ops-console's
   DB session to reach memory rows, so a bug or a malicious query is
   stopped by Postgres, not by code review?

## Decision

### 1. `scope_stats` — an app-maintained projection table

A new table `scope_stats(tenant_id, scope_id, scope_slug, scope_kind,
type, entry_count, last_updated_at, refreshed_at)` keyed on
`(tenant_id, scope_id, type)`. It carries `tenant_id` like every other
fact table here and runs under the same RLS posture (ENABLE + FORCE +
the tenant-isolation policy from ADR-0011).

**The private exclusion is a property of the schema**, not the
refresher:

- `scope_stats.scope_kind` has a `CHECK (scope_kind IN
  ('project','global'))`. A row with `scope_kind = 'private'` cannot
  exist in this table, even if a future refactor forgets the WHERE
  clause.
- The refresher's INSERT carries the matching `WHERE s.kind !=
  'private'` predicate. Two layers, the same intent.

### 2. `ScopeStatsRefresher` — single source of truth for the WHERE

A Quarkus `@Scheduled` bean recomputes the projection every 5 minutes
(configurable via `kumbuka.scope-stats.refresh-interval`). Two
statements per tenant: `DELETE FROM scope_stats` (RLS narrows to the
current tenant) then an `INSERT … SELECT … FROM memory JOIN scope
WHERE s.kind != 'private' GROUP BY …`.

OSS edition is single-tenant; the refresher resolves the tenant
through the standard `TenantResolver` (default singleton), gets the
GUC set by `@TenantBound`, and runs. The commercial edition swaps the
resolver and may extend this bean to iterate every tenant.

The refresher is **shared-only by construction**. A per-user private
*count* would itself leak (which users have how much private memory);
this projection never aggregates private rows in any shape.

### 3. `kumbuka_ops_reader` — the cross-tenant read role with no
   grant on `memory`

A new Postgres role created by the V6 migration:

```sql
CREATE ROLE kumbuka_ops_reader LOGIN BYPASSRLS PASSWORD '…';
GRANT USAGE ON SCHEMA public TO kumbuka_ops_reader;
GRANT SELECT ON scope_stats   TO kumbuka_ops_reader;
GRANT SELECT (id, tenant_id, slug, name, kind, fixed, archived,
              description, created_by, created_at)
              ON scope         TO kumbuka_ops_reader;
GRANT SELECT ON user_account   TO kumbuka_ops_reader;
GRANT SELECT ON team_settings  TO kumbuka_ops_reader;
GRANT SELECT ON team           TO kumbuka_ops_reader;
-- DELIBERATELY ABSENT: GRANT … ON memory TO kumbuka_ops_reader;
```

Two non-obvious choices here:

- **`BYPASSRLS` is intentional and load-bearing.** The provider
  operates cross-tenant by design — it must list all tenants, not just
  the one a session is bound to. The structural P1 guarantee comes
  from the GRANT shape that follows. RLS would have made this role
  see *nothing*; we need it to see *everything except* memory.
- **The missing GRANT on `memory` is the guarantee.** If a buggy or
  malicious query in ops-console tries `SELECT … FROM memory`, the
  ops-console's DB session — which connects as `kumbuka_ops_reader` —
  gets `ERROR: 42501 permission denied for table memory`. **P1 is a
  missing GRANT, not a missing endpoint.**

This is the database-level expression of decision D-OPS-4 in the
ops-console spec v2.

## Consequences

- The provider can list and detail tenants, see scope counts, see
  member metadata, see settings — but cannot read memory content.
  Period. The wall is in Postgres, not in application code.
- Member counts come from Keycloak (D-OPS-5) — the projection covers
  what the OSS DB owns (scopes + entries).
- Private container existence shows up via `scope` (the `kind =
  'private'` row that provisioning seeds). The console may render
  "private container exists, contents not accessible, no counts." It
  has no count to render even if it wanted one.
- The refresher writes through the `kumbuka` app role (NOBYPASSRLS),
  bound to the current tenant via `@TenantBound`. RLS narrows the
  cross-tenant aggregate to the bound tenant per pass.
- In tests, `quarkus.scheduler.enabled=false` keeps the background
  refresher out of the way; `ScopeStatsRefresherIT` calls the bean
  directly and asserts (a) private rows never land in the projection
  and (b) the `scope_kind` CHECK rejects a hand-rolled `INSERT` with
  `'private'`.
- Migration version `V5` is intentionally **skipped** here and left
  reserved for the C2 follow-up (shared-scope key uniqueness — per
  the multi-tenancy review note). `V6` is this migration.
- Operators who upgrade past V6 must arrange the password for
  `kumbuka_ops_reader` to be set to a real value via
  `ALTER ROLE … PASSWORD …`; the migration uses a placeholder so first
  boot does not fail on a missing variable.
