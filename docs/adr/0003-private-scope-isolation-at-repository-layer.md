# ADR-0003: Enforce private-scope isolation at the repository layer

- Status: Accepted
- Date: 2026-06-05

## Context

The strongest invariant of this system is:

> Private memories are visible/editable **only** to their owner. No other
> user, no admin, **never** the admin UI.

The cheapest way to enforce this is to filter at the API boundary — "if
endpoint is admin, exclude `private`; if endpoint is MCP, filter by
`owner_subject == caller`". That works until someone adds a new endpoint and
forgets the filter. The invariant is too important to depend on every future
contributor remembering a convention.

Alternatives considered:

- **Hibernate `@Filter`**: filter is enabled at session level. Disabling it
  is a one-line mistake; reviewers won't always catch a missing `enableFilter`
  call.
- **Postgres Row-Level Security**: strong, but requires per-request session
  variables (`SET LOCAL`) and adds operational complexity for the dev
  scaffold. Worth revisiting at scale.
- **Repository split**: two repository classes, where the admin one has no
  Java reference to private rows at all. Compile-time guarantee — you cannot
  call a method that doesn't exist.

## Decision

Split the data-access layer into two repository classes:

- `MemoryRepository` — used by **MCP tools only**. Every query implicitly
  filters by the calling user's `subject` (for private scope) and never
  returns another user's private rows. The caller's subject is injected from
  `SecurityIdentity`, not passed as an argument, so it cannot be spoofed by
  service-layer code.
- `SharedMemoryRepository` — used by **admin code paths only**. Every query
  hard-codes `scope.kind != 'private'` in JPQL. There is no method on this
  class that takes or returns private rows. There is no method to "switch
  scope type". Admin services depend on this class, not on `MemoryRepository`.

A unit test asserts via reflection / ArchUnit that admin packages do not
import `MemoryRepository`, and that `SharedMemoryRepository` has no method
whose JPQL contains the literal string `'private'` outside of a `!=`
predicate.

A separate **integration smoke test** (`PrivateIsolationSmokeIT`) runs the
full stack end-to-end: User A writes a private memory via MCP; User B (also
member) and User C (admin) attempt to read/list/delete it via every public
surface (MCP recall, MCP forget, admin API list, admin API delete). All must
return as if the memory does not exist. This test is a release gate.

## Consequences

- Some duplication between repositories (similar query shapes, different
  guards). This is intentional cost paid for a hard invariant.
- A new admin feature cannot accidentally surface private data — the type
  system won't let it.
- If we later add Postgres RLS, it layers cleanly on top of this without
  removing the application-layer guarantee.
- The `tenant_id` column (forward-compat for multi-tenant) is filtered on
  every query in both repositories, even though there is only one tenant in
  this edition.
