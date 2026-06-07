# kumbuka.ai — Design Handoff & Acceptance Review

Source: `kumbuka_ai.zip` (Claude Design return — React/JSX prototype + CSS + rendered screenshots).
Purpose: verify the design against the acceptance criteria and extract a build-ready
spec for the frontend and backend Claude Code sessions.

---

## A. Acceptance-criteria verification

| Criterion (from the design brief) | Status | Notes |
|---|---|---|
| Audience/tone: calm, precise developer-console feel | ✓ | Dark rail, dense tables, no marketing gloss. |
| Brand: paper / orange / navy | ✓ | Tokens present (see §B). **Accent hex differs from logo** — see §F-1. |
| Type system: Space Grotesk / Inter / JetBrains Mono | ✓ | Set as `--font-display/-body/-mono`. |
| **Private-scope guarantee, explicit & reassuring** | ✓✓ | Exemplary — surfaced in **5** places (scope browser panel, dashboard band, settings "locked" section, account, team/invite copy). Framed as a backend-enforced guarantee, not an omission. |
| Login screen | ✗ (by design) | Console assumes an authenticated session (BFF redirect to IdP). No in-app login — consistent with the OAuth topology, but confirm the redirect entry exists. See §F-3. |
| Overview / dashboard | ✓ | Stat cards, connector card, recent shared activity, member summary, type-distribution bars, private band. |
| Scope browser | ✓ | Global + project + archived groups; the persistent private panel; counts; per-scope menu. |
| Memory entries | ✓ | Sortable table (Type / Key / Content / Author / Updated / actions) **and** a cards layout; type chips; key in mono ("—" when empty); rel-time with full-date title. |
| Entry CRUD | ✓ | Side-panel editor (create/edit), row menu, destructive confirm modal. |
| Empty / loading / error states | ✓ | Empty scope (`design-system`), load skeleton, sync-error phase with retry (`billing-platform`), archived/read-only (`legacy-monolith`). |
| Scope management | ✓ | Create (name → kebab-slug id, id immutable on rename), rename, archive; global marked `fixed`. |
| Team & users | ✓ | Roles (admin/member), status (active/invited/disabled), invite flow (→ IdP), role hints. |
| Settings | ✓ | Default write-scope policy, who-may-create-scopes, connector details, private-memory locked section. |
| Components: type chips (6), scope selector, sort/filter table, side-panel editor, role badges, confirm | ✓ | All present. |
| Light + dark mode | ✓ | Full dual token sets + toggle; dark screenshots included. |
| Keyboard / focus / a11y | ✓ (claimed) | `aria-*`, `role`, `aria-sort`, focus-on-open in editors. Verify focus rings + tab order during build. |

**Beyond spec (useful additions the design introduced):**
Account screen (profile, password-via-IdP, MFA/authenticator, passkey, recovery codes, session revoke); cards layout as an alternative to the table; density toggle; **agent author** (entries can be written by the assistant, rendered distinctly); scope flags `archived`/`syncError`/`empty`; toast system; collapsible rail (248→64px).

**Verdict:** acceptance criteria are essentially fully met, with the private-scope guarantee notably strong. Open items are reconciliations, not gaps — see §F.

---

## B. Design tokens (verbatim — drop into the frontend)

```
--paper #F4F1EA   --paper-2 #EBE6DB   --ink #141820   --deep #0F1620
--navy  #2D4059   --accent #FF5B1F

fonts: display 'Space Grotesk' · body 'Inter' · mono 'JetBrains Mono'

entry-type colors (light):
  decision #2D4059 · convention #6F7540 · constraint #C44536
  open_question #C07B1E · glossary #5F5C82 · status #3E7C5E

spacing: 4 8 12 16 20 24 32 40 48 64   ·   rail width 248px (→64px collapsed)
```
Full light + dark variable sets live in `console.css` `:root` / `[data-theme]`.

---

## C. Data model (extracted from `data.jsx`)

**Memory entry**: `id`, `type` (enum, below), `key` (optional; lowercase, dot/kebab-namespaced — the assistant looks up by it), `content` (required), `author` (a user id **or** the special `agent`), `updated` (ISO). Lives inside a scope.
→ Backend should also persist `created_at` (design tracks only `updated`).

**Entry types** (fixed taxonomy, ordered): `decision`, `convention`, `constraint`, `open_question`, `glossary`, `status`. Each has a label + one-line description (in `ENTRY_TYPES`).

**Scope**: `id` (kebab slug), `name`, `kind` ∈ `global | project` (+ `private`, conceptual — never in console data), `fixed` (true for the single global), `desc`, flags: `archived` (read-only), `syncError`, `empty`. Exactly one `global`.

**User**: `id`, `name`, `email`, `initials`, `role` ∈ `admin | member`, `status` ∈ `active | invited | disabled`, `last` (last-seen), `self`.

**Connector**: `endpoint` (`https://memory.kumbuka.ai/mcp`), `client_id`, `client_secret` (masked, rotatable), `idp_name` (`Keycloak`).

Note: the console's scope data contains **only** global + project — `private` is structurally absent, which is the access invariant realised in the data layout.

---

## D. Derived API contract (what the console calls the backend for)

Read paths (admin/console context — **must never return `private`**):
- `GET /scopes` → scopes with kind, flags, entry counts
- `GET /scopes/{id}/entries` → entries (server applies access rule)
- `GET /users` ; `GET /settings` ; `GET /connector` (endpoint, client_id, masked secret)
- `GET /overview` → counts, recent shared activity, member summary

Write paths:
- `POST/PATCH/DELETE /scopes/{id}/entries/{eid}` (type, key, content)
- `POST /scopes` (name, id) ; `PATCH /scopes/{id}` (rename) ; archive
- `POST /users` (invite → creates account in Keycloak, emails enrolment link, **no password set here**) ; `PATCH /users/{id}` (role, enable/disable)
- `PATCH /settings` (writePolicy, defaultScope, createScopes)
- `POST /connector/secret:rotate` (old secret invalidated immediately)

MCP surface (separate, the AI client — **per authenticated user, includes their own private**):
- `/mcp` Streamable HTTP — `memory_remember / recall / forget / scopes / load_context`.

Account paths are largely **Keycloak** concerns (password, MFA, passkey, sessions) — decide wrap-vs-delegate (§F-4).

---

## E. Access-control rules to enforce (backend)

- `private`: readable/writable only by its owner via the MCP surface. **No console/admin endpoint may ever return private rows** — enforce at the data-access layer, not the UI. The design states this verbatim ("enforced by the backend, not by configuration").
- `global` + `project`: team-shared; members read, admins manage.
- Author may be a human user **or** the `agent` identity; persist whichever wrote it.

---

## F. Open decisions / reconciliations before the build

1. **Accent color mismatch.** Logo mark = `#DC5C30`; UI accent token = `#FF5B1F`. Pick one canonical brand orange and align the other (recolour the logo SVGs, or change the token). One source of truth.
2. **Default write-scope policy conflict.** The earlier Code-session brief set *default write scope = `private`*. The design's policy is `{ ask | project | global }`, default **`ask`** (assistant proposes, member confirms), with **no `private` option** — because the policy governs *shared* writes, while private is always available to the user separately. **Recommendation:** adopt the design's model — default `ask`, options ask/project/global — and update the backend brief accordingly. Treat private as the user's always-on personal space, not the default target.
3. **Login / entry.** No in-app login screen; the console assumes a BFF session. Confirm the unauthenticated entry redirects to Keycloak and back to the backend callback.
4. **Account-screen scope.** The design wraps password/MFA/passkey/session management. Decide how much the console implements (via Keycloak Account REST / Admin API) vs linking out to Keycloak's own account console. Affects effort materially.
5. **`created_at`.** Add it server-side; the design only carries `updated`.
6. **Stale assets.** `.scratch/*` screenshots and some `assets/` + `uploads/` files still show **remember.ai** branding/logo. The code is on kumbuka.ai. Clean before publishing.
7. **Prototype storage.** The prototype uses `localStorage` for theme/route/layout/density — fine as a prototype, but those are user preferences that belong to the backend/session in the real app (and `localStorage` is unavailable in some embeds).

---

## G. Routing to the build sessions

**Frontend session** gets: §B tokens, the component/screen inventory (§A), the JSX prototype as the visual source of truth, and §D as the API it consumes. Rebuild in React+Next.js+Tailwind; keep the prototype's structure but replace mock `data.jsx` with the real API and remove `localStorage`.

**Backend session** gets: §C data model, §D contract, §E access rules, §F-2 settings/policy, and the Keycloak integration points (invite/enrolment, roles, enable/disable, connector-secret rotation, the masked-secret read).

---

## H. Branding & naming conventions

Wherever an identifier names the **product, org, or a deployable/package**, brand it
**kumbuka**. Canonical scheme (both sessions follow it so nothing ships half-branded):

- **Repo / org:** GitHub org `kumbuka-ai`, repo `kumbuka`.
- **Backend (Java/Maven):** groupId `ai.kumbuka` (reverse of kumbuka.ai); root package
  `ai.kumbuka.*` (e.g. `ai.kumbuka.memory`, `ai.kumbuka.mcp`, `ai.kumbuka.admin`);
  artifactId `kumbuka-server`.
- **Frontend (npm):** package name `@kumbuka-ai/console` (or `kumbuka-console`).
- **Container images:** `ghcr.io/kumbuka-ai/kumbuka-backend`, `ghcr.io/kumbuka-ai/kumbuka-console`.
- **Compose services:** `kumbuka-backend`, `kumbuka-console` (alongside `keycloak`, `postgres`).
- **Keycloak realm:** `kumbuka`. Clients: `kumbuka-backend` (service account),
  `kumbuka-admin` (BFF), `kumbuka-connector` (claude.ai public client).
- **Databases:** app DB `kumbuka`; Keycloak DB `keycloak`.
- **MCP server identity:** advertises itself as `kumbuka`.
- **Hostnames (config, never hardcoded):** console `kumbuka.ai`, MCP endpoint
  `memory.kumbuka.ai` (or `mcp.kumbuka.ai`), Keycloak `auth.kumbuka.ai`. Keep as env/config.

**Rebrand the scaffold:** the existing `docker-compose.yml` + `keycloak/realm-import/`
still use `memory`-prefixed realm/clients/db and a `claude-connector` client — rename them to
the `kumbuka` scheme above as part of the backend session.

**One deliberate exception — keep functional, not brand-prefixed:** the MCP tool verbs stay
`memory_remember / memory_recall / memory_forget / memory_scopes / memory_load_context`. The
model reads these; functional clarity beats brand noise. (Brand-prefixing to `kumbuka_*` is a
one-line change if ever wanted — but the recommendation is to leave them functional.)
