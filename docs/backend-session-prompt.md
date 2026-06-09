# Claude Code — Backend Session: kumbuka.ai memory server

Build the **backend** for kumbuka.ai: a team memory system exposed as a remote
**MCP server over Streamable HTTP** plus an **admin REST API**, secured by
Keycloak OAuth, in Docker. Java 21 + Quarkus + PostgreSQL.

## Read first — authoritative, in this order
1. `docs/DESIGN_HANDOFF.md` — the binding spec: data model (§C), API contract (§D),
   access rules (§E), resolved decisions (§F), routing (§G).
2. `design/prototype/data.jsx` — concrete data shapes and seed examples.
3. The existing `docker-compose.yml` and `keycloak/realm-import/memory-realm.json`
   (headless Keycloak + Postgres are already scaffolded; realm `memory` with the
   `memory-backend` service-account and `claude-connector` public client).

**The handoff is authoritative. Where the prototype disagrees, the handoff wins.**
Ignore stale `remember.ai` branding in the prototype.

## Resolved decisions (do not re-litigate)
- **Default write-scope policy = `ask`** (assistant proposes, member confirms);
  options `ask | project | global`. *Not* `private`. Private is each user's
  always-available personal space, never the default write target.
- Apply the **branding & naming conventions in §H** — groupId `ai.kumbuka`, packages
  `ai.kumbuka.*`, the realm/clients/app-DB rebranded from `memory*`/`claude-connector` to
  the `kumbuka` scheme, images under `kumbuka-ai`.
- Persist **`created_at`** (the prototype carries only `updated`).
- Identity lives in Keycloak; the backend is the only thing that talks to Keycloak.

## Stack
Java 21, Quarkus (Maven). Extensions: `quarkus-mcp-server-http` (Streamable HTTP —
**not** SSE), `quarkus-oidc`, `quarkus-rest` + Jackson, `quarkus-hibernate-orm-panache`,
`quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-keycloak-admin-rest-client`,
`quarkus-smallrye-health`.

## Auth topology — two OIDC roles, implement exactly (handoff §E + earlier topology)
1. **Resource server (bearer)** for `/mcp`: claude.ai discovers the AS via Protected
   Resource Metadata at `/.well-known/oauth-protected-resource` → the Keycloak `memory`
   realm; validate audience-bound tokens locally. Token `sub` = acting user; realm role
   (`member`/`admin`) = authz.
2. **Confidential web-app client** (`kumbuka-admin`) for the admin REST API as a **BFF**:
   the frontend redirects the user to Keycloak and back to a backend callback; the
   backend holds the session and issues an HttpOnly cookie. The frontend never holds
   tokens and never calls Keycloak.
All Keycloak admin operations (user invite/role/enable-disable) go through the backend
using the `kumbuka-backend` service account. Never pass a client token through to Keycloak.

## Data model & persistence (handoff §C)
Entities: `Memory` (id, tenant_id [defaulted, multi-tenant forward-compat], owner_subject,
scope, type [enum: decision/convention/constraint/open_question/glossary/status], key
[nullable, lowercase dot/kebab], content, created_at, updated_at); `Scope` (id [kebab
slug], name, kind [global|project], fixed [the single global], description, archived,
created_by); plus user role/status read from Keycloak. Author may be a user `sub` **or**
the special `agent` identity. Postgres; Flyway migrations. Exactly one `global` scope.

## MCP surface — `/mcp`, per authenticated user, **includes their own private**
`memory_remember(content, type, scope?, key?)`, `memory_recall(scope?, type?, query?,
include_global?)`, `memory_forget(scope, key?|id?)`, `memory_scopes()`,
`memory_load_context(scope?)`. Identity from the token `sub`.

## Admin REST API — the contract the frontend consumes (handoff §D) — **never returns private**
Scopes (+counts) · scope entries · entry create/update/delete · scope create/rename/archive ·
users (list/invite→Keycloak enrolment link, no password set here/role/enable-disable) ·
settings (writePolicy/defaultScope/createScopes) · connector (endpoint, client_id, **masked**
secret, rotate — old secret invalid immediately) · overview aggregates.

## Access control — enforce at the data-access layer (handoff §E)
`private` is owner-only and reachable **only** via `/mcp`. **No** admin/console endpoint may
ever return private rows — the admin code paths must have no route to them. `global`/`project`
are team-shared; admins manage. The `agent` author is a valid author identity.

## Deliverables & structure
`backend/` Quarkus app; extend `keycloak/realm-import/kumbuka-realm.json` with the
`kumbuka-admin` confidential web-app client (authorization-code, backend callback),
rebranding the realm + clients to the `kumbuka` scheme per §H; add the
backend service to `docker-compose.yml` (depends on keycloak healthy); Flyway migrations;
`README.md`; `docs/adr/`. **Tests:** `@QuarkusTest` + Testcontainers (Postgres + Keycloak),
including a **mandatory smoke test proving private isolation** — an admin/console query can
never surface another user's private rows.

## Discipline
Produce a build plan + repo layout first and **wait for my go** before scaffolding. Do not
invent architectural decisions — ask, or record an ADR and surface it. English artifacts.
AGPL-3.0. Verify the OAuth flow with **MCP Inspector** before the claude.ai web client.
