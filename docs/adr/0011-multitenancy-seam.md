# ADR-0011: multi-tenancy seam — `TenantResolver` SPI + two-layer enforcement

- Status: Accepted
- Date: 2026-06-07
- Supersedes part of: [ADR-0005](0005-flyway-for-schema-with-tenant-id-forward-compat.md)
  — the "forward-compatibility" promise is now redeemed.
- Builds on: [ADR-0003](0003-private-scope-isolation-at-repository-layer.md) — the
  private-memory guarantee now sits *under* the tenant axis.
- References: `docs/decisions/kumbuka-decisions-multitenancy-opencore.md` (the
  edition boundary — open-core vs commercial).

## Context

`kumbuka-server` is the open-source edition of an open-core product. The
commercial edition adds real multi-tenant operation: subdomain / org-claim
resolution, tenant management, provisioning, quotas. The decision
document mandates that **multi-tenancy is commercial** but **the OSS edition
ships the seam** — `tenant_id` threading, a resolver SPI, structural
enforcement, with a single-tenant default — so the commercial edition can
bind a real resolver **without patching OSS**.

The cardinal rule (decision doc §6) is that data-access tenancy must be
enforced **structurally, in two layers**. We do not rely on per-query
discipline. Two layers were chosen because each catches a different class
of mistake: Hibernate's `@TenantId` catches forgotten predicates in JPQL,
and Postgres RLS catches forgotten filters in raw SQL, native queries,
and code paths that go around the ORM entirely.

ADR-0005 prepared the schema by carrying `tenant_id NOT NULL` on every
user-data table and shipping per-tenant uniqueness for scope slug,
`global`, and `private`. The seam in this ADR redeems that promise.

## Decision

### SPI — `ai.kumbuka.tenancy` (frozen at v1.0.0)

```java
package ai.kumbuka.tenancy;

import java.util.UUID;

public interface TenantResolver {
    /** The data tenant id for the current request scope. Never null. */
    UUID currentTenant();
}
```

- The package, the interface, and this single-method signature are the
  **frozen contract** the commercial edition depends on. Adding methods
  or changing the signature is a breaking change and requires a v2.0.0
  SPI line + a parallel migration path for the commercial edition.
- The OSS edition ships `DefaultSingleTenantResolver`, an
  `@ApplicationScoped @DefaultBean` that always returns
  `kumbuka.tenant-id` from `MemoryConfig`. **The commercial edition
  replaces this bean by registering its own `@ApplicationScoped
  TenantResolver` — Quarkus picks the non-default bean automatically.**
  No `@Alternative`, no priority dance, no OSS patch.

### Internal extension surface (not part of the SPI)

```java
package ai.kumbuka.tenancy;

public interface TenantContext {
    java.util.UUID current();
    AutoCloseable bind(java.util.UUID tenantId);
}
```

`TenantContext` exists for callers that have no request context —
background jobs, integration tests, control-plane code. It is **not**
part of the SPI: the commercial edition is free to ship its own
implementation, but `TenantContext`'s shape can change with semver
discipline since no third-party code depends on it.

### Effective-tenant rule (M1)

Both the Hibernate `CurrentTenantIdentifierResolver` and the Postgres
session-GUC setter read from **`TenantContext.current()`** — the bound
value if `bind()` is active on this thread, otherwise the result of the
`TenantResolver`. They never read the `TenantResolver` directly. This
prevents a split brain where a job that called `bind(B)` would steer
Hibernate to tenant B but leave the GUC on the default.

### Layer 1 — Hibernate `@TenantId` (DISCRIMINATOR)

- `quarkus.hibernate-orm.multitenant=DISCRIMINATOR`
- `@TenantId` on `Memory.tenantId`, `Scope.tenantId`,
  `UserAccount.tenantId`, `Team.tenantId`, `TeamSettings.tenantId`.
- `CurrentTenantIdentifierResolver` returns
  `TenantContext.current().toString()` (Hibernate uses strings as
  discriminators; we round-trip UUIDs).

### Layer 2 — Postgres RLS

- `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` on the
  five tenant-owned tables (`memory`, `scope`, `user_account`,
  `team_settings`, `team`).
- Policies: `USING (tenant_id = current_setting('app.tenant_id', true)::uuid)`
  and the same in `WITH CHECK`. `missing_ok=true` returns NULL when the
  GUC is unset, which the equality fails closed against.
- `SET LOCAL app.tenant_id = '<uuid>'` runs at the start of every
  transaction the application opens (`@TransactionScoped` bean).
- Migrations run as the same role; pure DDL is not filtered. Future
  seed/data migrations are protected by a Flyway `beforeEachMigrate`
  callback that sets `app.tenant_id` to the singleton tenant inside the
  migration transaction.
- A separate `BYPASSRLS` migration role is deferred to the commercial
  edition.

### Tenant axis vs private owner axis (M2)

RLS in this ADR enforces the **tenant axis only**. The
within-tenant "private = owner-only" guarantee remains where ADR-0003
put it — at the repository / route layer, with no admin/console code
path reaching `kind = 'private'` and the MCP path filtering on
`owner_subject = sub`. Hardening the owner axis into a second RLS
policy is optional follow-up; it does not belong to this seam.

## Consequences

- The OSS edition runs a single tenant, but every read and every write
  crosses the same machinery the commercial edition uses. The seam
  isn't a parallel code path — it *is* the code path.
- Background jobs, integration tests, and control-plane operations must
  open a `try (var bound = tenantContext.bind(tenantId)) { ... }`
  block. Anything that ignores this gets an empty result set (RLS) and
  Hibernate refuses to persist rows under no tenant.
- Repository code that previously held manual `tenantId = ?` predicates
  drops them in this commit. The two structural layers are
  authoritative; redundant predicates would falsely imply enforcement
  lives in the WHERE clause.
- The `kumbuka-server` Maven artifact's published surface includes
  `ai.kumbuka.tenancy.TenantResolver`. Versioning of the SPI is tracked
  in this ADR's title line: SPI v1.0.0. A future SPI break gets a new
  ADR that supersedes this one's contract clause.
- The commercial edition's only required code is a `@ApplicationScoped
  TenantResolver` bean. It is not required to touch entities,
  migrations, repositories, or the request filter.

## Verification

The acceptance gate is the cross-tenant isolation IT (`CrossTenantIsolationIT`)
plus the existing `AdminPrivateInvariantTest`. The IT seeds two tenants
directly via JDBC and proves four properties on a real Postgres
(Testcontainers):

- **Hibernate path.** `bind(A)` → no tenant B rows; symmetric for `bind(B)`.
- **RLS path.** `SET LOCAL app.tenant_id` to A → only A rows; to B → only B
  rows; unset → empty.
- **Private invariant under tenancy.** Real `recall(callerSubject=X)` from
  the MCP repository, with `bind(A)` and a same-`subject` X planted under
  both tenants, returns only A's private rows.
- **Write isolation.** A `bind(A)` write that tries to set
  `tenant_id = B` on the row fails closed via RLS `WITH CHECK`.
