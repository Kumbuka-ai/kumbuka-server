# Claude Code — Audit Session: kumbuka.ai full-repository review

Perform a single, complete, **read-only** audit of the kumbuka.ai monorepo
(Quarkus backend + Next.js console + Keycloak/Postgres/Caddy stack). Run four
**scans** and produce two **assessments**, then write one report. Change no
source code.

## Read first — authoritative, in this order
1. `docs/kumbuka-concept.md` — what the product is, why it exists, the ratified
   decisions, current state.
2. `docs/DESIGN_HANDOFF.md` — the binding spec: data model (§C), API contract
   (§D), access rules (§E), open decisions (§F), branding & naming (§H).
3. `docs/backend-session-prompt.md` and `docs/frontend-session-prompt.md` — what
   each build session was instructed to do.
4. `docs/adr/` and the ratified decision notes (`kumbuka-decisions-*`) — settled
   choices that must not be re-litigated.
5. Only then the code: `backend/`, `frontend/`, `docker-compose.yml`,
   `keycloak/realm-import/`, the Caddy config, CI, migrations.

**Establish the intended design first, then judge the code against that intent —
not against your own assumptions.** Where the handoff and the prototype/code
disagree, the handoff is authoritative.

## Ground rules
- **READ-ONLY.** Do not edit, refactor, format, or "fix" anything. No commits,
  no branches. The only thing you may write is the report under `docs/audit/`.
- Cite every finding with `file:line`. Reproduce only minimal snippets needed to
  make the point.
- Every finding gets a **severity** (Critical / High / Medium / Low / Info) and a
  **category** (one of the four scans).
- Separate **"violates the spec / is a real defect"** from **"I would have done it
  differently."** Mark the latter clearly as opinion.
- No false confidence. If a claim needs runtime verification you can't do
  statically, say so and tag it `unverified`. Do not assert a vulnerability you
  haven't traced.

---

## Scan 1 — Security vulnerabilities

### 1a. The private-memory invariant (highest priority — this is the product's backbone)
Per concept §6 and handoff §E: **no** admin/console/REST code path may ever read
or return `private` rows, enforced at the **data-access layer**, not the UI.
- Trace **every** read path (controller/resource → service → Panache/repository
  query) and prove private rows are unreachable from any non-`/mcp` surface.
  Watch for: missing scope-kind predicates, predicates that can be bypassed
  (default-include, `OR` widening, optional filters that default off), and
  aggregates/overview counts that silently include private entries.
- Verify the `/mcp` surface scopes strictly to the token `sub` — no cross-user
  leakage via `scope`, `key`, or `id` parameters.
- Confirm the **mandatory private-isolation test** exists and actually proves
  isolation (an admin/console query cannot surface another user's private rows) —
  not a tautological test that always passes.
- Confirm disabling a user leaves their private memory intact and unreadable by
  others.

### 1b. Auth / OAuth topology
- **BFF integrity:** the frontend must never hold tokens or call Keycloak
  directly (except the redirect to sign in). Look for tokens in client bundles,
  cookies readable by JS, or fetches to Keycloak from the browser.
- Session cookie: `HttpOnly` + `Secure` + sane `SameSite`; CSRF protection on all
  state-changing admin routes / Server Actions.
- Bearer validation on `/mcp`: issuer, audience binding, signature, expiry;
  reject `alg: none`; correct `/.well-known/oauth-protected-resource` metadata.
- Authorization: realm role (`member`/`admin`) actually enforced server-side —
  can a `member` token reach admin endpoints? Is authz on the **server action /
  endpoint**, not just hidden in the UI?
- All Keycloak admin ops go through the backend service account; no client token
  is ever forwarded to Keycloak.
- Connector client uses PKCE; the client secret is a real kill-switch (rotation
  invalidates the old secret immediately).

### 1c. General web/app security
- Injection (SQL via Panache string building, log injection), SSRF, path
  traversal, XXE.
- Secrets committed to source / compose / realm-import / `.env`; default or weak
  credentials; the "masked" connector secret must be masked **server-side**, not
  just visually hidden in CSS/markup.
- CORS, security headers, rate-limiting on auth and write endpoints.
- Server-side input validation: entry `content`/`key`, scope slug — slug/key
  regex enforced in the backend, not only the form.
- Next.js: no secrets in client bundles; `server-only` code never imported into
  client components; every mutation re-checks authz server-side.
- Dependency CVEs (Maven + npm): flag known-vulnerable versions.
- Error handling that leaks stack traces or internal detail to clients.

---

## Scan 2 — Architecture inconsistencies

Judge the code against the **ratified decisions** and against itself.
- Decisions implemented as ratified? — MCP over **Streamable HTTP, not SSE**;
  **Server Actions + `revalidateTag` + `useOptimistic`, no React Query**; Radix
  primitives + custom presentation; Tailwind **v4** `@theme`; **surrogate PK +
  immutable slug**; `created_at` persisted; default write-policy **`ask`** (never
  `private`); exactly **one** `global` scope enforced; runtime fallback to `ask`
  for an invalid `defaultScope` without mutating config.
- Layering: resources/controllers free of business logic; data-access isolated;
  the BFF boundary clean and one-directional.
- Module/package structure matches `ai.kumbuka.*` (`memory`/`mcp`/`admin`); no
  bleaking of concerns across them.
- **Contract drift:** real REST API ↔ handoff §D; frontend API client ↔ backend
  responses (field names, nullability, `updated` vs `created_at`, enum values).
- **Branding consistency (§H):** any leftover `memory*` realm/clients/db,
  `claude-connector`, `remember.ai` strings/assets, the wrong accent
  (`#DC5C30` vs canonical `#FF5B1F`), or the placeholder navy. One source of
  truth, or drift?
- Hostnames/secrets as env/config, never hardcoded (console, `memory.kumbuka.ai`,
  `auth.kumbuka.ai`).
- Flyway discipline: forward-only, no edited already-applied migrations.
- Frontend invariant: all **five** private-guarantee surfaces present (scope
  browser, dashboard band, settings locked section, account, team/invite copy),
  and no code path that renders private.

---

## Scan 3 — Bad code (quality)
- Correctness bugs, race conditions, N+1 queries, unbounded queries / missing
  pagination.
- Error handling: swallowed exceptions, overbroad catches, missing/incorrect
  transaction boundaries.
- Dead code, duplication, copy-paste, god classes/components, deep prop-drilling.
- Tests: missing coverage on critical paths, tests that assert nothing, no
  negative-path tests, fixtures that don't reflect reality.
- Resource leaks, blocking calls on reactive/event-loop threads (Quarkus),
  missing timeouts on outbound calls.
- Accessibility regressions vs the spec: `aria-sort`, focus-on-open, focus traps,
  visible focus rings, keyboard tab order.
- Type safety: `any` in TypeScript, raw types / unchecked nullables in Java.

---

## Scan 4 — AI-slop
Signs the code was generated but not understood or owned:
- Hallucinated APIs, libraries, Quarkus extensions, or config keys that don't
  exist or don't resolve.
- Comments that restate the obvious; leftover `TODO`/`FIXME`/`"in a real app you
  would…"` placeholders; explanatory prose where code should speak.
- **Invented decisions** that contradict the handoff (settled choices
  re-litigated).
- Off-the-shelf boilerplate that doesn't fit the project (generic CRUD scaffolds,
  unused generated files, sample/demo code left in).
- Style discontinuities between files suggesting different generations stitched
  together without reconciliation.
- Mocks/stubs left where real implementation was required — including the
  frontend's "mock behind the API client": is it cleanly swappable, or has it
  ossified into the real path?
- Plausible-but-wrong code: compiles and reads well but doesn't do what it claims;
  defensive handling of impossible states while real edge cases go unhandled.
- README/docs describing features that don't exist in the code.

---

## Assessment 1 — Code maturity
Give a single verdict — **prototype / alpha / beta / production-candidate** — with
a short scorecard and narrative across: correctness, security posture, test
coverage **and quality**, error handling & observability, build/deploy
reproducibility, documentation, and adherence to the project's own ratified
decisions. Be honest; do not grade on a curve. Justify the verdict in 1–2
paragraphs.

## Assessment 2 — Gap analysis to a real, usable application
What stands between this and something a real team could deploy and trust?
Organize gaps as:
- **Blocking** — must fix before any real use; anything touching the private
  invariant or auth lands here first.
- **Functional** — specified-but-missing or half-built features vs handoff §A/§D.
- **Operational** — secrets management, migrations, healthchecks, logging/metrics,
  backups, rate limits, the Caddy/edge config, container hardening, CI/CD.
- **Productionization** — environment config, multi-tenancy posture, deferred
  full-text search, performance/load unknowns.

For each gap: what's missing, why it matters, rough effort (**S / M / L**), and a
suggested sequence to close them.

---

## Output
Write the report to `docs/audit/AUDIT-<YYYY-MM-DD>.md` (English). Structure:
1. **Executive summary** (≤1 page): top risks, maturity verdict, a clear go/no-go
   for a pilot deployment.
2. **Findings** by category (the four scans). Each finding: id, severity,
   category, `file:line`, description, impact, recommendation.
3. **The two assessments.**
4. **Appendix — coverage:** what you reviewed, what you couldn't review, and every
   `unverified` item.

Prioritize ruthlessly: lead with the few things that actually matter. Do not pad.

## Discipline
**Plan first.** Report the repo layout you found and your review plan, and **wait
for my go** before producing the full report. Do not invent decisions; where the
code's intent is ambiguous, note the ambiguity rather than guessing. English
artifacts. Read-only throughout.
