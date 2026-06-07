# ADR-0007: Scope identity = surrogate UUID PK + immutable slug

- Status: Accepted
- Date: 2026-06-05
- Decision-ref: D4 (ratified by JBA, 2026-06-05)

## Context

The console (handoff §C) addresses scopes by **slug** — a kebab-case string
that doubles as the URL identity (`/scopes/atlas-web`) and the key the AI
assistant uses to talk about a scope by name. The MCP tools likewise take a
slug for the `scope` argument.

Two database layouts could back this:

- **(A) Slug is the primary key** — `scope.id TEXT PRIMARY KEY`. Memory
  rows reference the scope by slug. Renaming a scope means rewriting every
  memory's FK; making the slug immutable solves that, but locks the slug
  to whatever the creator typed first.
- **(B) Surrogate UUID PK + a separate unique slug** —
  `scope.id UUID PRIMARY KEY`, `scope.slug TEXT UNIQUE`. Memory rows
  reference the UUID. Slugs can be kept immutable (the convention) without
  the DB structure depending on that convention; if we ever need to
  reassign a slug we just `UPDATE scope SET slug = ...` and nothing else
  changes.

## Decision

Adopt **(B)**.

- `scope.id` stays UUID (it already was, from V1).
- `scope.slug` is added in V2 as `TEXT NOT NULL`, `UNIQUE (tenant_id, slug)`,
  with a kebab-case `CHECK` constraint.
- The convention is that slugs are **immutable** once a scope is created —
  same as Stripe-style stable ids in URLs. We do not provide a "rename
  slug" API. (The schema permits it as an emergency operator action; the
  API surface does not.)
- All external addressing (admin REST, MCP tools, frontend routes) uses
  the slug; internal joins keep using the UUID.

## Consequences

- The slug constraint enforces format at write time —
  `^[a-z0-9]+(-[a-z0-9]+)*$`. Scope creation rejects bad input early.
- Renaming a scope (the `name`/display label) is independent of the slug —
  the spec already separates them ("name → display, id → address"). No
  schema change needed to rename.
- The repository layer exposes `findBySlug` / `requireBySlug`; methods
  that previously addressed by `name` are gone.
- No V2 data migration is needed for FKs — `memory.scope_id` continues to
  reference `scope.id` (UUID).
- The two seed scopes from V1 (`private`, `global`) are backfilled
  `slug = name` in V2; both happen to already be valid kebab strings.
