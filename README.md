# kumbuka-server — shared team memory for AI assistants, served over MCP

![License](https://img.shields.io/badge/license-AGPL_v3-FF5B1F?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-2D4059?style=flat-square&logo=openjdk&logoColor=F4F1EA)
![Quarkus](https://img.shields.io/badge/Quarkus-2D4059?style=flat-square&logo=quarkus&logoColor=F4F1EA)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-2D4059?style=flat-square&logo=postgresql&logoColor=F4F1EA)
![Keycloak](https://img.shields.io/badge/Keycloak-OAuth_2.1-2D4059?style=flat-square&logo=keycloak&logoColor=F4F1EA)
![MCP](https://img.shields.io/badge/MCP-Streamable_HTTP-FF5B1F?style=flat-square)
![Private memory](https://img.shields.io/badge/private_memory-structurally_guaranteed-141820?style=flat-square&labelColor=FF5B1F)
![CI](https://img.shields.io/github/actions/workflow/status/kumbuka-ai/kumbuka-server/ci.yml?style=flat-square&label=CI&color=FF5B1F)

Self-hostable team memory system exposed as a remote **Model Context Protocol**
(MCP) server over Streamable HTTP. Reachable from claude.ai and other
MCP-capable AI clients. Everything runs in Docker, orchestrated by a
single `docker-compose.yml`.

> **Repo layout.** This is the OSS server repository (`kumbuka-server`): the
> Quarkus backend (a multi-module Maven build: `server` + the `spi` module),
> Caddy/Postgres ops, and the ADR record. The Next.js admin console lives in
> its own repo
> [`kumbuka-console`](https://github.com/kumbuka-ai/kumbuka-console); wire it
> in via a `compose.override.yml` (see the `kumbuka-console` block comment
> in `docker-compose.yml`). The Keycloak image, realm templates, and theme
> live in [`kumbuka-keycloak`](https://github.com/kumbuka-ai/kumbuka-keycloak).
> A downstream composition build can provide multi-tenant resolution on the
> `ai.kumbuka.tenancy.TenantResolver` SPI frozen here (ADR-0011).

License: **AGPL-3.0**.

> **Status.** The MCP surface, the admin REST API, the OAuth integration with
> Keycloak, and the private-isolation invariant are in place, covered by the
> unit and integration test suites. The admin console is built and maintained
> in [`kumbuka-console`](https://github.com/kumbuka-ai/kumbuka-console). See
> `docs/adr/` for the architectural record.

---

## Contents

- [Architecture](#architecture)
- [Tool surface](#tool-surface)
- [Data model & access control](#data-model--access-control)
- [Settings](#settings)
- [Quick start (Dev)](#quick-start-dev)
- [Connecting Claude clients](#connecting-claude-clients)
  - [claude.ai (web) — custom connector](#claudeai-web--custom-connector)
  - [Claude Desktop](#claude-desktop)
  - [Claude Code](#claude-code)
  - [Claude Mobile](#claude-mobile)
- [Using kumbuka in a project — best practices](#using-kumbuka-in-a-project--best-practices)
- [Suggested prompts](#suggested-prompts)
- [Verification](#verification)
- [Security warnings](#security-warnings)
- [Repo layout](#repo-layout)
- [Contributing](#contributing)

---

## Architecture

```
                ┌──────────────┐
                │   Caddy      │   only public service (auto-TLS)
                └──────┬───────┘
        ┌──────────────┼──────────────┬──────────────┐
        │              │              │              │
   /  (console)   /api/* + /mcp + /.well-known   /auth/*
        │              │                              │
        ▼              ▼                              ▼
  ┌──────────┐   ┌──────────┐                  ┌──────────┐
  │ Next.js  │   │ Quarkus  │ ───── Admin ───▶ │ Keycloak │
  │ console  │   │ backend  │       REST       │ realm:   │
  │ (BFF)    │   │          │                  │ kumbuka  │
  └──────────┘   └────┬─────┘                  └────┬─────┘
                      │                             ▼
                      ▼                       (postgres: keycloak)
              ┌──────────────┐
              │ PostgreSQL   │
              │ db: kumbuka  │
              └──────────────┘
```

The backend plays **two** OIDC roles against the Keycloak realm `kumbuka`:

1. **Resource server (bearer)** at `/mcp`. AI clients discover the
   authorization server via `/.well-known/oauth-protected-resource`
   (RFC 9728) and run the OAuth authorization-code flow with PKCE. A client
   identifies itself through its published client metadata or dynamic client
   registration at first authorization — there is no hand-entered client id
   and no client secret. It then calls `/mcp` with a bearer token.
   The token's `sub` claim is the acting user; the realm role
   (`member` / `admin`) is the authorisation context.
2. **Confidential web-app client** `kumbuka-admin` for the admin console
   as a **BFF**. The browser never holds tokens — the Quarkus backend
   keeps the OIDC session and issues an HttpOnly cookie. User management
   goes through the backend via the confidential service-account client
   `kumbuka-backend`. The frontend has zero Keycloak knowledge.

---

## Tool surface

The MCP endpoint `/mcp` exposes five tools to the connected model:

| Tool                  | What it does                                                                                  |
| --------------------- | --------------------------------------------------------------------------------------------- |
| `memory_remember`     | Append (or upsert on `key`) a memory. Caller picks `scope`, `type`, optional `key`.            |
| `memory_recall`       | Read memories. Filters: `scope`, `type`, substring `query`, optional `include_global`.         |
| `memory_forget`       | Delete by `id` or by `(scope, key)`. Private rows protected by owner check.                    |
| `memory_scopes`       | List scopes visible to the caller (private + every shared scope).                              |
| `memory_load_context` | Typed digest grouped by type (decision / constraint / convention / glossary / open_question / status), capped per group. |

There is also an MCP resource `memory://{scope}` listing scope contents.

Tool returns are **structured JSON** (MCP `structuredContent`) so the
model can parse fields reliably rather than re-parsing prose.

---

## Data model & access control

Six fixed entry types, two scope kinds, one invariant.

**Types** (taxonomy is intentionally small):

| Type            | Use for                                                              |
| --------------- | -------------------------------------------------------------------- |
| `decision`      | A settled choice the team committed to.                              |
| `convention`    | A shared way of doing things; the default.                           |
| `constraint`    | A hard boundary that must not be crossed.                            |
| `open_question` | Unresolved; needs an owner and an answer.                            |
| `glossary`      | A term defined so everyone means the same thing.                     |
| `status`        | The current state of something in motion.                            |

**Scopes:**

- `private` — visible/editable **only** to its owner. Always available to
  every user as their personal space. **No** other user, **no** admin, and
  **never** the admin console can reach it (see ADR-0003).
- `project` — team-shared, addressed by a stable kebab slug
  (`atlas-web`, `billing-platform`, …). Members read, admins manage.
- `global` — exactly one per team, the always-on baseline the assistant
  reads first.

**Invariant.** Private rows are unreachable from any admin code path —
enforced at the data-access layer via a separate repository class with
no method that can return private rows. The release-gate smoke test
(`PrivateIsolationTest`) proves it on every build.

---

## Settings

A singleton `team_settings` row drives runtime policy:

| Setting          | Values                                  | Meaning                                            |
| ---------------- | --------------------------------------- | -------------------------------------------------- |
| `writePolicy`    | `ask` (default) / `project` / `global`  | What `memory_remember` does when `scope` is omitted. `ask` returns a structured prompt asking the user; private is **never** the default. |
| `defaultScopeSlug` | a project slug                        | Only used when `writePolicy = project`. If it goes archived/missing, the resolver falls back to `ask` at runtime without mutating the row. |
| `createScopes`   | `admins` (default) / `members`          | Who may create new project scopes.                 |

Admins edit these in the console; the changes take effect immediately.

---

## Quick start (Dev)

```bash
cp .env.example .env
# edit .env — set domain + secrets (or accept the dev defaults)
just up                       # postgres + keycloak + backend + caddy
```

Then visit `https://<your-domain>` (whatever you set `KUMBUKA_DOMAIN` to).

Common targets:

```bash
just up                 # bring the full stack up
just infra              # only postgres + keycloak + caddy
just logs               # tail logs; e.g. just logs kumbuka-backend
just ps                 # show running services
just psql               # psql shell into the kumbuka database
just test               # backend unit tests
just clean              # tear down + DROP volumes (destroys data)
```

The login screen, the account console, and the invitation / verification /
password-reset emails are all themed by the **kumbuka** Keycloak theme, which
— along with both realm definitions and the production image — now lives in its
own repository: **[kumbuka-keycloak](https://github.com/kumbuka-ai/kumbuka-keycloak)**.
Both dev and prod consume the same published image
`ghcr.io/kumbuka-ai/kumbuka-keycloak`, pinned via `KEYCLOAK_VERSION`. Theme and
realm edits, and the build/verify loop, happen in that repo.

To exercise the email surfaces, bring up MailHog:

```bash
docker compose --profile app --profile dev-mail up -d
# MailHog UI:   http://localhost:8025
```

See [ADR-0010](docs/adr/0010-keycloak-theme.md) for the theme's scope, parent
themes, and packaging decisions.

> Dev test users are no longer seeded by a realm import here — create them via
> the Keycloak admin console or `kcadm.sh` against the running `kumbuka` realm.

---

## Production deploy

See [ADR-0012](docs/adr/0012-deploy-topology.md) for the topology
decisions. In short: the production stack does **not** ship its own
Caddy. It assumes an existing Caddy is already running on the host and
joins its docker network. The kumbuka images are pulled from GHCR by
tag.

Three pieces in this repo together describe a production deploy:

| File | Role |
|---|---|
| `docker-compose.prod.yml` | The stack (postgres + kumbuka-keycloak + kumbuka-backend + kumbuka-console). Images come from GHCR by version tag; no Caddy here. |
| `deploy/caddy/kumbuka.caddy` | Caddyfile snippet for the host Caddy. `import` it from the host Caddyfile. |
| `.env.prod.example` | Production env template — set `KUMBUKA_VERSION`, `CADDY_NETWORK`, hostnames, secrets. |

Minimal hand-deploy on a host that's already set up:

```bash
docker login ghcr.io -u <your-gh-user> --password-stdin < /etc/kumbuka/ghcr.pat
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

How deploys are automated beyond that — pull scripts, health gates,
rollback — is a property of your hosting environment, not of this
repository. Treat the files above as the reference shape for a
compose-based deploy behind an existing reverse proxy, and wire them
into your own operations.

---

## Connecting Claude clients

All clients use the same OAuth-discovered flow: the URL is all you
enter — there is no client id and no client secret. The client first hits
`/mcp`, gets a `401` with a `WWW-Authenticate` header pointing at
`/.well-known/oauth-protected-resource`, then runs the authorization-code
flow with PKCE against Keycloak, identifying itself through its published
client metadata or dynamic client registration at first authorization.
The backend validates the token's `aud` claim before letting any tool
call through.

### claude.ai (web) — custom connector

1. **Settings → Connectors → Add custom connector.**
2. Fill in:
   - **Name:** `kumbuka`
   - **URL:** `https://mcp.kumbuka.ai/mcp` (an example — your deployment's MCP host)
3. Click **Connect**. claude.ai discovers the auth server via PRM, opens
   a Keycloak login window, and on consent stores the resulting token
   server-side.
4. The five `memory_*` tools should now appear under the connector. Pin
   the connector to any project where you want kumbuka active.

### Claude Desktop

Claude Desktop's in-app **Connectors** UI takes the same URL:

1. **Settings → Connectors → Add custom connector.**
2. **URL:** `https://mcp.kumbuka.ai/mcp` (your deployment's MCP host).
3. Sign in to Keycloak when prompted.

If your Desktop build is older and doesn't expose the in-app connector
manager, fall back to the [mcp-remote](https://github.com/geelen/mcp-remote)
shim in `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "kumbuka": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "https://mcp.kumbuka.ai/mcp"
      ]
    }
  }
}
```

`mcp-remote` opens a browser window for the OAuth flow on first connect
and caches the refresh token locally.

### Claude Code

Add the server once globally and Claude Code picks it up across projects:

```bash
claude mcp add --transport http kumbuka https://mcp.kumbuka.ai/mcp
```

Or per project, add to `.claude/settings.json`:

```json
{
  "mcpServers": {
    "kumbuka": {
      "type": "http",
      "url": "https://mcp.kumbuka.ai/mcp"
    }
  }
}
```

Run `claude` once; you'll be redirected to Keycloak to authorise. After
consent the token is cached and subsequent sessions reuse it.

> **Note.** Claude Code only loads MCP servers from `.claude/settings.json`
> at session start. If you change the config mid-session, restart `claude`.

### Claude Mobile

Same custom-connector dialog as claude.ai web. The OAuth flow opens the
in-app browser; on consent the connector becomes available to enable in
any project.

---

## Using kumbuka in a project — best practices

The taxonomy and the scope split are deliberately small. Keeping them
small is what makes the memory useful — a dumping ground for every chat
fragment quickly becomes noise the model has to wade through.

### When to write — and when not to

**Write** when the conversation produced:

- A choice the team is now committed to (`decision`).
- A new convention or default ("we use Conventional Commits") (`convention`).
- A hard boundary worth surfacing on future similar work
  (`constraint` — "money is integer minor units, never floats").
- A question that's now blocking and needs an owner (`open_question`).
- A term whose meaning the team formalises (`glossary`).
- A status that someone in two days will need to know (`status` — "v2.4
  canary at 10% since 2026-06-02").

**Don't write**:

- Ephemeral exploration ("what if we tried X?" — once you decide, write
  the decision, not the exploration).
- Speculation the team hasn't actually committed to.
- Information that's already obviously discoverable from code or commit
  messages.
- Conversation transcripts. Memory is for the conclusion, not the trail.

### Pick a scope on purpose

- **`private`** — your personal scratchpad. Drafts, "remind me later",
  open threads only you care about. The team can't see it; even the admin
  console can't see it. Use freely but don't park team knowledge here.
- **`project`** — the default home for project-specific work. Tie the
  slug to the repo / service name (`atlas-web`, `billing-platform`).
  Members write here when working on that thing.
- **`global`** — the team-wide baseline. Anything that applies across
  every project goes here: language conventions, infra rules,
  compliance constraints, the team-wide glossary. Keep it lean.

If unsure, ask the user. The `writePolicy=ask` default exists exactly
for this — `memory_remember` without `scope` returns a structured
"please specify" prompt that lists available scopes, so the model can
relay the question instead of guessing.

### Naming keys

Optional, but recommended for anything the model might want to update
later. Convention: lowercase, dot- or kebab-separated namespaces.

```
auth.session-length           // open question about token lifetime
db.system-of-record           // a decision
infra.iac                     // a convention
release.v2-4                  // current status
term.canary                   // glossary entry
bundle.budget                 // a constraint
```

The same key in the same scope, by the same author, upserts in place
on the next `memory_remember`. Pick a slug-like key when the entry has
a single canonical answer; leave key empty for one-shot status notes.

### The `agent` author

When a memory is written by Claude through `/mcp`, the row carries
`source = mcp`. The console renders an "agent" badge next to the
author's name to make the provenance obvious. The actual author is
still the real human under whose token the write happened (ADR-0008)
— there's no `agent` Keycloak account.

---

## Suggested prompts

These are starting points. Edit them to your team's voice and the
specific project once you've used kumbuka for a week or two and you
notice the friction points.

### Project Instructions (claude.ai Projects)

Paste into a Claude project's **Instructions** so every chat in that
project loads context and follows the same memory protocol:

````
You have a kumbuka memory connector attached. Use it as follows.

ON CONVERSATION START
- Call `memory_load_context` with no scope to get the typed digest
  across global + all shared scopes I can see. Read the decisions and
  constraints first — they bound what's possible.
- If we're working on a specific project, call `memory_load_context`
  again with `scope=<that project's slug>` for the project view.

DURING THE CONVERSATION
When something in the discussion is worth remembering, propose a
write — don't write silently. Use these types:
- `decision` for a settled choice we just made
- `convention` for a new shared default
- `constraint` for a hard boundary
- `open_question` for unresolved threads with no owner yet
- `glossary` when we define a term
- `status` for the current state of something in motion

For every proposed write tell me:
1. the type
2. the scope (`global`, a project slug, or `private` — pick `private`
   only when I asked you to)
3. a stable `key` (lowercase, dot/kebab) if the entry should be
   updatable later
4. a one- or two-sentence content

If `memory_remember` returns a `PromptForScope` (the team's writePolicy
is `ask`), surface its `reason` to me verbatim and ask which scope.
Don't silently fall back to `private`.

WHEN I ASK A QUESTION ABOUT "WHAT DID WE DECIDE / WHAT'S THE CONVENTION"
Call `memory_recall` with a substring query before answering from your
own model knowledge. Cite the memory's `key` (or id) in your answer.

NEVER
- Write conversation transcripts to memory.
- Write to my `private` scope unless I explicitly ask.
- Drop entries with the same `key` without telling me you're overwriting.
````

### `CLAUDE.md` for Claude Code

For repos using Claude Code, add this to the project's `CLAUDE.md` (or
extend an existing one). Edit the project slug to match the scope you
created in the console.

````markdown
# Working with kumbuka memory

This repo has a kumbuka MCP connector configured. The project slug for
this codebase is `atlas-web` (rename when copying).

## Load context at session start

Before reading any code, call:

```
memory_load_context(scope="atlas-web")
memory_load_context(scope="global")
```

Read the `decision` and `constraint` buckets first. If the user's
request conflicts with a stored constraint, surface the constraint
before making the change — don't silently violate it.

## Capture what the session produces

- A new architectural decision → `memory_remember type=decision`
  in `atlas-web` (or `global` if it spans projects).
- A coding convention worth applying repo-wide → `type=convention`.
- A new "don't do this" → `type=constraint`.
- An open thread the user wants tracked → `type=open_question`.
- New domain term → `type=glossary`.

Always:
- Use a stable `key` for anything updatable
  (e.g. `db.system-of-record`, `bundle.budget`, `forms.validation`).
- Propose the scope and wait for the user to confirm. Don't default to
  `private`.
- Skip the write if the discussion was exploratory and we didn't land
  on a conclusion.

## Updating existing memories

If `memory_recall` finds an entry with the same `key` you're about to
write, you're updating it — surface the existing content to the user
before overwriting so they can confirm the change is intentional.
````

### App developer prompt (Claude API)

If you're building an application against the Claude API and want it to
use kumbuka, pass the MCP server in the request and add a system prompt
along these lines:

```
You have access to the kumbuka memory MCP server.

Workflow:
1. At session start, call memory_load_context() once with no scope.
2. When you make a recommendation, ground it in memories you recall
   first. Cite the memory key in your answer when relevant.
3. When the user gives explicit feedback that should persist
   ("from now on we...", "don't do X anymore"), propose a memory
   write with the appropriate type and ask for the scope.
4. Never write to scope=private without explicit user instruction.
5. Don't write speculative or in-progress content.
```

---

## Verification

### Automated (fast)

```bash
cd backend && mvn test
```

Runs the in-process test suite, including the **private-isolation smoke test**
(`PrivateIsolationTest` — the release-gate invariant from ADR-0003) and the
admin REST surface checks. No Docker needed beyond DevServices Postgres.

### Automated (slow, Testcontainers + Keycloak)

```bash
cd backend && mvn verify -Pintegration
```

Runs the Testcontainers-based integration tests, including the OAuth
end-to-end flow (`E2EOAuthIntegrationIT`) against a real Keycloak under
a dedicated OIDC-enabled test profile.

### Manual — MCP Inspector

1. `cp .env.example .env`, set secrets, `just up`.
2. Run the inspector locally:
   ```bash
   npx @modelcontextprotocol/inspector
   ```
3. In Inspector → **Add Server** → Transport: **Streamable HTTP** →
   URL: `https://<your-domain>/mcp`.
4. Inspector triggers the OAuth flow: Keycloak prompts for a user in the
   `kumbuka` realm (create one via the admin console / `kcadm.sh` first —
   dev test users are no longer auto-seeded); on consent it returns a token.
5. Tools tab should list the five `memory_*` tools. Call
   `memory_remember scope=private` with some content; in a second
   Inspector session signed in as a different user, call `memory_recall
   scope=private` and confirm the private content is **not** visible.

Acceptance: the Inspector flow succeeds AND the second user does not
see the first user's private content. Failure of the second check is a
security incident.

---

## Security warnings

- **Never** expose the service without TLS **and** OAuth. The MCP
  endpoint trusts bearer tokens; without TLS, tokens leak.
- All secrets live in `.env`. **Never commit `.env`.** The repo only
  ships `.env.example` with placeholder values.
- Change every default password and client secret in `.env` (client
  secrets are rendered into the realms by the `kumbuka-keycloak` image at
  boot) before deploying anywhere beyond your laptop.
- The `kumbuka-keycloak` image runs `kc.sh start --optimized` with proper
  `KC_HOSTNAME` and HTTPS termination via Caddy — both dev and prod use it.
- Private memories are an inviolable invariant. `PrivateIsolationTest`
  must stay green; treat its failure as a security incident, not a
  flake.
- A connected AI client can be cut off at any time by **disabling its
  registered client** in Keycloak — the connector-level kill-switch
  (there is no connector secret; clients register themselves at first
  authorization). On a suspected token leak, disable the client and
  audit the Keycloak event log for token issuance during the suspected
  window.

---

## Repo layout

```
backend/                multi-module Maven build
  server/               Quarkus + Java 21 — the MCP server, BFF, and admin API
    src/main/java/ai/kumbuka/
      admin/            admin REST resources (scopes, entries, users,
                        settings, overview, /auth/me)
      auth/             OIDC tenant resolution
      config/           typed config mapping (kumbuka.*)
      domain/           JPA entities + enums + the singleton TeamSettings
      keycloak/         Keycloak Admin REST client wrapper
      mcp/              @Tool methods + structured DTOs
      repo/             MemoryRepository (MCP path),
                        SharedMemoryRepository (admin path — no private),
                        ScopeRepository, TeamSettingsRepository
      service/          write-policy resolution and domain services
      wellknown/        /.well-known/oauth-protected-resource (RFC 9728)
    src/main/resources/
      application.properties
      db/migration/     Flyway migrations
  spi/                  the tenancy SPI published for composition builds
postgres/               init-db.sh (creates keycloak + kumbuka DBs)
ops/                    operator-side compose + scripts consuming released images
deploy/caddy/           Caddyfile snippet for a host Caddy
docs/                   the ADR record (docs/adr/) and historical design documents
assets/brand/           kumbuka logos in light + dark + lockup variants
docker-compose.yml      dev orchestration
docker-compose.prod.yml production stack reference
Caddyfile               TLS termination + path routing (dev)
justfile                common dev targets
.env.example            template — copy to .env
```

## Contributing

Decisions land as ADRs under `docs/adr/`. Code changes that touch
access-control or auth topology require updating the relevant ADR in the
same PR. The private-scope invariant is non-negotiable — if you find
yourself adding a method to `SharedMemoryRepository` that could return a
private row, stop and re-read ADR-0003.
