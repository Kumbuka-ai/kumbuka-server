# ADR-0008: Agent authorship derived from source channel

- Status: Accepted
- Date: 2026-06-05
- Decision-ref: D5 (ratified by JBA, 2026-06-05)

## Context

The design prototype carries an `agent` author identity — entries written
"by the assistant" render differently from entries written by humans.
Three implementations were on the table:

- **(A) Client-flagged**: an `as_agent=true` argument on `memory_remember`,
  admin-gated. The MCP caller would assert it is writing on behalf of the
  agent.
- **(B) Dedicated Keycloak user**: an `agent` Keycloak account with its
  own `sub`. Writes from this user render as agent-authored.
- **(C) Server-derived from channel**: every memory carries a
  `source ∈ {console, mcp}`. `/mcp` writes are agent-authored; admin
  REST writes are user-authored. The acting human's `sub` is still
  recorded in `owner_subject` — we lose nothing.

(A) requires trusting client-passed flags. (B) creates a fake user and
splits identity (who *actually* wrote this row?). (C) lets the server
classify based on a fact it owns (which endpoint the call came in on),
preserves the real subject, and matches the prototype's UI rendering
exactly.

## Decision

Adopt **(C)**: persist a `source` column on every memory row,
server-derived from the request channel.

- `memory.source VARCHAR(16) NOT NULL CHECK source IN ('console', 'mcp')`.
- `memory.owner_subject` continues to hold the real Keycloak `sub` of the
  acting human — no `agent` pseudo-subject.
- MCP tool layer sets `source = MCP` on every write; admin REST endpoints
  set `source = CONSOLE`. The repository does not default `source`; it
  throws if the caller forgot to set it (cheap, loud guard).
- UI renders the "via assistant" badge when `source = MCP`. The author
  cell still shows the human's display name (which is the truth).

## Consequences

- Two endpoints cannot accidentally cross-contaminate authorship: the
  routing topology already separates them (`/mcp` vs `/api/*`), so the
  source assignment matches the OIDC tenant assignment 1:1.
- The private-isolation invariant (ADR-0003) is **unaffected** —
  visibility still depends on `owner_subject`, which still holds the
  real human subject. The source column doesn't widen or narrow access.
- The `agent` Keycloak user does not exist and does not need to. If we
  ever want a finer distinction (e.g. "the assistant *autonomously*
  wrote this without a prompt"), we add another column then; we don't
  retrofit auth.
- The handoff prototype's "agent author" rendering needs a small
  reinterpretation in the frontend: it triggers off `source`, not off
  `author_id === 'agent'`. The frontend session prompt should note this.
