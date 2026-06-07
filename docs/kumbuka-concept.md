# kumbuka — Project Concept & Orientation

> A shared, persistent memory for AI assistants working with a team — exposed as a
> remote MCP server, managed through an admin console, with a hard guarantee that each
> person's private memory stays private.

This document orients anyone (human or a fresh assistant session) joining the project.
It explains **what** kumbuka is, **why** it exists, **how** it is built, **what has been
decided**, and **where things stand**. For build-level detail, see the companion documents
listed in §10.

---

## 1. What kumbuka is

**kumbuka** is an open-source **team memory system for AI assistants**. It gives a team a
durable, shared place for the knowledge an assistant should carry across conversations —
decisions, conventions, constraints, definitions, open questions, and status — and serves
that knowledge to AI clients (Claude and other MCP-capable assistants) over a remote
**MCP server**. A web **admin console** lets the team curate the shared memory.

The name is Swahili: *kumbuka* is the imperative "remember!" (infinitive *kukumbuka*). The
product lives at **kumbuka.ai**.

The defining promise: shared memory is shared and curatable, but **each member's private
memory is theirs alone** and is never exposed to admins, to the console, or through the
connector. That guarantee is enforced in the backend, not by a setting (see §6).

---

## 2. Why it exists

AI assistants are stateless between sessions. Teams using them re-explain the same
context repeatedly — "we use Postgres as the system of record," "money is integer minor
units, never floats," "service names are kebab-case." That steering knowledge is exactly
what an assistant should *remember* and apply without being re-told.

kumbuka makes that knowledge a first-class, team-owned asset:

- It is **work-steering knowledge**, not a content dump. It captures the rules, decisions,
  and definitions that should shape how an assistant works — not a copy of the team's
  documents or source (those stay in their own systems).
- It is **shared and curatable**: the team sees and edits what the assistant relies on,
  rather than each person accumulating an opaque, divergent private context.
- It is **portable**: any MCP-capable assistant can read and write it through one endpoint.
- It respects a **personal/shared boundary**: people keep a private scope for their own
  working memory, separate from what the team curates together.

### Origin

The project began as a personal tool — a local stdio MCP "memory" server (Python + SQLite)
that injected work-steering rules into the author's own Claude Code / Desktop sessions. It
proved the model: a small, typed set of steering rules, deterministically read at session
start, materially improves how an assistant behaves on a long-running project. kumbuka
generalises that into an always-on, multi-user, multi-client **team** product.

---

## 3. Core concepts (domain model)

**Memory entry** — one unit of remembered knowledge. Fields: a stable surrogate id; the
owning **scope**; a **type** (taxonomy below); an optional **key** (lowercase,
dot/kebab-namespaced — the assistant looks entries up by it, e.g. `db.system-of-record`);
the **content** (plain statement); the **author** (a human or the assistant); and
`created_at` / `updated_at` timestamps.

**Entry taxonomy** (fixed, six types, each with a distinct color in the UI):

| Type | Meaning |
|---|---|
| `decision` | A settled choice the team has committed to. |
| `convention` | A shared default way of doing things. |
| `constraint` | A hard boundary that must not be crossed. |
| `open_question` | Unresolved; needs an owner and an answer. |
| `glossary` | A term defined so everyone means the same thing. |
| `status` | The current state of something in motion. |

**Scope** — a container of entries with an access kind:

- **private** — per user, **owner-only**. Reachable only by that user through the MCP
  surface. **Never** appears in the console or any admin/team-facing API. One per member.
- **project** — team-shared, created to carve the shared memory into spaces (e.g.
  `billing-platform`). Members read/write; admins manage.
- **global** — exactly **one**, organization-wide; the baseline the assistant reads first.

A scope has a surrogate primary key plus a unique, immutable **slug** (the address the
assistant and UI use), a display name, a kind, an `archived` (read-only) flag, and a
description.

**Authorship** — an entry's author is either a human (their authenticated identity) or the
**assistant** ("agent"). Provenance is **derived server-side from the write channel**:
writes through the console are attributed to the signed-in human; writes through the MCP
surface are marked "via assistant" while still recording the real human subject behind the
session. The UI's "agent" badge means `source = mcp`. There is no separate login for the
assistant, and no client-supplied flag decides authorship.

---

## 4. How an assistant uses it (the MCP surface)

kumbuka exposes a remote **MCP server over Streamable HTTP** at a `/mcp` endpoint, scoped to
the **authenticated user** (so it can serve that user's own private scope alongside the
shared ones). The tools are intentionally functional, not brand-named:

- `memory_remember(content, type, scope?, key?)` — write/append (or upsert on key).
- `memory_recall(scope?, type?, query?, include_global?)` — read with filters.
- `memory_forget(scope, key?|id?)` — remove.
- `memory_scopes()` — list scopes the user may see.
- `memory_load_context(scope?)` — a typed, ready-to-inject digest of the relevant rules.

Where a new memory lands when the assistant isn't told a scope is governed by the team's
**default write-scope policy** (see §7).

---

## 5. Architecture

A single **Docker Compose** stack, all artifacts in **English**, licensed **Apache-2.0**.

**Backend** — **Java 21 + Quarkus**. Key extensions: `quarkus-mcp-server-http` (Streamable
HTTP — not SSE), `quarkus-oidc`, `quarkus-rest` + Jackson, `quarkus-hibernate-orm-panache`,
`quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-keycloak-admin-rest-client`,
`quarkus-smallrye-health`. It serves both the `/mcp` surface and the console's admin REST
API, and is the **only** component that talks to Keycloak.

**Database** — **PostgreSQL** (system of record for memory; Flyway migrations;
full-text search via `tsvector` is a later addition).

**Identity** — **Keycloak**, run headless (realm imported at start; users provisioned via
the Admin API). OAuth 2.1 is mandatory for the remote MCP surface.

**Frontend** — **React + Next.js (App Router) + Tailwind v4 + TypeScript**: the admin
console. Tailwind v4's CSS-first `@theme` consumes the design's CSS variables almost
verbatim. Mutations use **Server Actions + `revalidateTag`** with React 19 `useOptimistic`
for optimistic UI (no React Query). Interactive widgets (dialogs, menus, popovers) use
**Radix primitives**, fully restyled to the design tokens; everything else is custom.

**Edge** — **Caddy** for routing the console, the `/mcp` endpoint, and the auth host.

### Auth topology (the important part)

The backend plays **two OIDC roles**:

1. **Resource server (bearer)** for `/mcp`. AI clients discover the authorization server via
   Protected Resource Metadata (`/.well-known/oauth-protected-resource` → the Keycloak
   `kumbuka` realm) and present audience-bound tokens. The token subject is the acting user;
   the realm role (`member` / `admin`) drives authorization.
2. **Confidential web-app client** (`kumbuka-admin`) as a **BFF** for the console. The
   browser is redirected to Keycloak and back to a backend callback; the backend holds the
   session and issues an HttpOnly cookie. **The frontend never holds tokens and never calls
   Keycloak directly** (except being redirected there to sign in).

The **claude.ai connector** is a **confidential client + PKCE** (`kumbuka-connector`). PKCE
is sent regardless of client type; the secret provides a real connector-level kill-switch
(rotate to revoke) and matches claude.ai's pre-registered id+secret path. Remote connectors
require a paid claude.ai plan; mobile inherits web-added servers.

### The admin console (what the team sees)

Screens: **Overview** (scope counts, recent shared activity, member summary, the connector
card, type-distribution); **Scope browser** (global + project + archived scopes, the entries
table/cards with type filter, search, sort, and loading/empty/error states, plus the
persistent private-guarantee panel); **Team & users** (roles, invite, enable/disable);
**Settings** (the policies in §7 and the connector details); **Account** (profile in-app;
password/MFA/passkey/sessions linked out to the Keycloak account console).

**The console's read APIs never return private entries** — there is no code path from an
admin/team surface to anyone's private memory.

---

## 6. The private-memory guarantee (a defining principle)

This is the product's backbone, not a feature flag:

- A member's **private** scope is owned by them and reachable **only** by them, **only**
  through the MCP surface under their own authenticated session.
- **No** admin, **no** console screen, and **no** team-facing API can read it. It is
  enforced at the **data-access layer** — the admin code paths have no route to private rows
  — **not** by a configuration toggle that could be flipped.
- The console surfaces this promise in five places (scope browser, dashboard, settings,
  account, and the invite flow) so it reads as a deliberate guarantee, not an omission.
- Disabling a user suspends their account but **leaves their private memory untouched and
  theirs**.

When in doubt, this guarantee wins over convenience.

---

## 7. Settings & policies

- **Default write-scope policy** — where the assistant writes when not told a scope:
  `ask` (propose and have the member confirm — the default, safest for mixed teams),
  `project` (the active project scope, with a fallback), or `global`. *Private is always
  available to the user directly; it is never the team default target.*
- **Who may create project scopes** — `admins` (default) or `members`. The single `global`
  scope is fixed: it cannot be created or removed.
- **Invalid `defaultScope`** (e.g. the configured scope was archived/deleted) — the backend
  falls back to `ask` **at runtime without mutating the stored config**, shows an admin
  banner, and warns proactively at archive/delete time.
- **Connector details** — endpoint URL, client id, a masked client secret, and a **rotate**
  action (rotating invalidates the old secret immediately).

---

## 8. Branding & identity

- **Name / domain / org:** kumbuka · kumbuka.ai · GitHub `kumbuka-ai` · repo `kumbuka`.
- **Colors:** paper `#F4F1EA`, ink `#141820`, navy `#2D4059`, **accent `#FF5B1F`** (the
  canonical brand orange — logo and UI share it).
- **Type:** Space Grotesk (display), Inter (body), JetBrains Mono (mono).
- **Logo:** an interlaced-knot mark plus the wordmark; horizontal, stacked, `kumbuka.ai`
  two-tone, and white-knockout variants exist, all in `#FF5B1F`.
- **Naming convention:** anything that names the product, org, or a deployable/package is
  branded `kumbuka` — groupId `ai.kumbuka`, packages `ai.kumbuka.*`, artifact
  `kumbuka-server`, npm `@kumbuka-ai/console`, images `ghcr.io/kumbuka-ai/*`, compose
  services `kumbuka-backend` / `kumbuka-console`, Keycloak realm `kumbuka` with clients
  `kumbuka-backend` / `kumbuka-admin` / `kumbuka-connector`, app DB `kumbuka`. **Exception:**
  the MCP tool verbs stay functional (`memory_*`), since the model reads them and clarity
  beats brand noise.

---

## 9. Current state

- **Design:** an admin-console prototype was produced in Claude Design, reviewed against the
  acceptance criteria (essentially fully met, with the private guarantee implemented
  exemplarily), and distilled into a build-ready handoff.
- **Specs & decisions:** the design handoff, a backend session prompt, and a frontend session
  prompt are written. Two rounds of open questions (backend Q1–Q5, frontend A–E) have been
  answered and **ratified** (see §10 for the notes and §11 for the digest).
- **Brand:** name, colors, naming conventions, and logo set are finalised on `#FF5B1F`.
- **Deployment scaffold:** a Docker Compose stack with headless Keycloak + Postgres and a
  realm import exists and was syntax-validated; it still carries `memory`-prefixed
  realm/clients/db and is to be rebranded to the `kumbuka` scheme during the backend build.
- **Not yet built:** the backend service and the frontend console themselves — that is the
  next step (see §12).

---

## 10. Companion documents

Place these in the repo so build sessions read them from disk. The **handoff is the
authoritative spec**; where it and the prototype disagree, the handoff wins.

- **`DESIGN_HANDOFF.md`** — the binding build spec: acceptance-criteria matrix (§A), design
  tokens (§B), data model (§C), API contract (§D), access rules (§E), open
  decisions/discrepancies (§F), session routing (§G), branding & naming (§H).
- **`backend-session-prompt.md`** — the Claude Code brief for the Quarkus backend.
- **`frontend-session-prompt.md`** — the Claude Code brief for the Next.js console.
- **Decision notes** — `kumbuka-decisions-backend-q1-5.md` and
  `kumbuka-decisions-frontend-a-e.md` (ratified, append-only).
- **`design/prototype/`** — the Claude Design return (JSX/CSS/screenshots) as the visual
  reference. Note: its screenshots/assets still show the old `remember.ai` branding — ignore
  that; the brand is kumbuka / `#FF5B1F`.
- **`assets/brand/`** — the current logo set (`#FF5B1F`).

---

## 11. Key decisions digest (do not re-litigate)

**Architecture:** Quarkus backend; MCP over Streamable HTTP; Postgres; Keycloak (headless);
Next.js + Tailwind v4 console; single Docker Compose; Apache-2.0. OAuth 2.1 via Keycloak,
backend in two OIDC roles, BFF for the console, frontend never holds tokens.

**Ratified Q&A:**
- *Connector client* = **confidential + PKCE** (secret is a real kill-switch; matches the
  design and claude.ai's pre-registered path).
- *Account screen* = **hybrid link-out**: profile in-app (display name editable; email
  display-only), credentials/MFA/passkey/sessions link to the Keycloak account console.
- *Invalid `defaultScope`* = **runtime fallback to `ask`**, no config mutation, admin banner,
  proactive warning on archive/delete.
- *Scope identity* = **surrogate PK + unique immutable slug** from the start (no migration
  needed).
- *Agent authorship* = **server-derived from channel**, never a client flag; no Keycloak user
  for the assistant.
- *Component strategy* = **custom presentation + Radix primitives** for dialogs/menus.
- *Tailwind* = **v4**. *i18n* = **none now**. *Mutations* = **Server Actions + `revalidateTag`
  + `useOptimistic`**, no React Query.

**Default write-scope policy** = `ask` (not private).

---

## 12. Open items & next steps

1. **Run the build**, backend first (it defines the API contract the frontend consumes), then
   the frontend — two Claude Code sessions, both reading the handoff.
2. **Rebrand the scaffold** (`memory*` realm/clients/db → the `kumbuka` scheme) as part of the
   backend session.
3. **Minor, undecided:** the wordmark navy is the placeholder `#15233F` while the design token
   is `#2D4059` — align if a single source of truth is wanted.
4. **Deferred by design:** multi-tenancy (a `tenant_id` is defaulted now for forward
   compatibility); full-text search (`tsvector`); any "autonomously-generated vs.
   user-dictated" finer authorship distinction (optional field, later).

---

## 13. Working conventions

- **All file artifacts are in English.** Conversation may be in German.
- **Append-only documentation** with explicit **ratification** — decisions are recorded, not
  silently changed; supersessions are noted.
- **Claude Code discipline:** produce a plan first and wait for go before scaffolding; do not
  invent architectural decisions (ask, or record an ADR); verify the OAuth flow with the MCP
  Inspector before wiring the web client.
- **The handoff supersedes the prototype** on any conflict.

---

## 14. Glossary

- **MCP** — Model Context Protocol; the open protocol AI clients use to call tools/resources.
  Here served over **Streamable HTTP** (the modern transport, replacing SSE).
- **Scope / kind** — a container of memory entries; its kind is `private`, `project`, or
  `global`.
- **Connector** — the configured link an AI client (e.g. claude.ai) uses to reach the `/mcp`
  endpoint: an endpoint URL, a client id, and (here) a client secret.
- **BFF** — Backend-for-Frontend; the backend holds the user session and brokers all
  privileged calls so the browser never handles tokens.
- **IdP** — identity provider; here **Keycloak**.
- **Private guarantee** — the invariant that a member's private memory is never exposed to
  admins, the console, or the connector (see §6).
