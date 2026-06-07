# ADR-0005: Flyway for schema, with `tenant_id` forward-compatibility

- Status: Accepted
- Date: 2026-06-05

## Context

The data model needs to survive at least one significant evolution: a future
multi-tenant edition. The spec is explicit — include `tenant_id` now,
defaulted, but do not build multi-tenancy. We also want explicit, reviewable
schema migrations rather than Hibernate auto-DDL ("`update`" is a footgun in
team settings — it silently drifts).

## Decision

- Use **Flyway** (`quarkus-flyway`) for all DDL. Hibernate runs with
  `quarkus.hibernate-orm.database.generation=validate` — it asserts the
  schema matches the entity model but never mutates the schema.
- Every table that holds user data carries a `tenant_id UUID NOT NULL`
  column. In this edition there is exactly one tenant — the singleton `Team`
  row — and `tenant_id` defaults to its id at insert time via a default
  expression or an application-level constant. Every query in both
  repositories filters on `tenant_id = :currentTenant`.
- The first migration (`V1__init.sql`) creates: `team`, `user_account`,
  `scope`, `memory`. Indexes: `(tenant_id, scope_id)`, `(tenant_id, owner_subject, scope_id)`,
  `(tenant_id, scope_id, key)` UNIQUE where `key IS NOT NULL`.
- Full-text search is **not** added in V1 — it lands as a later migration
  introducing `tsvector` + GIN index. The schema as shipped is forward-
  compatible with adding it.

## Consequences

- Schema changes always show up as a new versioned SQL file in PRs — clear
  review surface.
- The multi-tenant edition can introduce `tenant_id` constraints (NOT NULL
  remains, default expression removed, RLS policies added) without a data
  rewrite.
- Hibernate `validate` mode catches entity/schema drift at startup; failing
  fast in CI is preferred over silent prod surprises.
