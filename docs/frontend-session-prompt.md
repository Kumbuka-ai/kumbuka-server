# Claude Code — Frontend Session: kumbuka.ai admin console

Build the **admin console** for kumbuka.ai — **React + Next.js + Tailwind** — that
consumes the backend API and is faithful to the Claude Design prototype. Runs in Docker.

## Read first — authoritative, in this order
1. `docs/DESIGN_HANDOFF.md` — the binding spec: tokens (§B), screens & components (§A),
   API contract (§D), access rules (§E), resolved decisions (§F).
2. `design/prototype/` — the visual + behavioral reference. `console.css` is the token
   source; `app.jsx / screens.jsx / dashboard.jsx / team.jsx / settings.jsx / account.jsx /
   editors.jsx / ui.jsx` show structure and components; `.scratch/*.png` show the rendered
   look. **Match this design.**
3. `assets/brand/` — the current logos (accent `#FF5B1F`).

**The handoff is authoritative. Where the prototype disagrees, the handoff wins.** The
prototype's screenshots and bundled logos still show the old `remember.ai` branding and the
old accent — **ignore them**. Use **kumbuka**, accent **`#FF5B1F`**, and the logos in
`assets/brand/`.

## Resolved decisions (do not re-litigate)
- Tokens come from `console.css` `:root`: paper `#F4F1EA`, ink `#141820`, navy `#2D4059`,
  **accent `#FF5B1F`**; type-chip colors per type; fonts Space Grotesk / Inter / JetBrains
  Mono; the spacing scale; rail 248→64px collapsed. Map these into the Tailwind theme via
  CSS variables; keep the light + dark sets.
- **Drop `localStorage`** (the prototype used it for theme/route/layout/density). Persist
  user preferences via the backend/session; theme may default and live in a cookie.
- Settings write-scope policy UI = `ask | project | global` (not `private`).
- Apply the **branding & naming conventions in §H** — npm package `@kumbuka-ai/console`,
  container image `kumbuka-console`, compose service `kumbuka-console`.

## Stack & auth
Next.js (App Router) + TypeScript + Tailwind. Server components fetch through the backend
session. **BFF auth:** the frontend calls only the backend `/api`; it **never** talks to
Keycloak and never holds tokens. Unauthenticated → a "Sign in" action triggers the backend's
OIDC redirect to Keycloak and back; the backend sets an HttpOnly session cookie. No in-app
login form.

## Screens & components (handoff §A / prototype)
Overview (stat cards, connector card with copy, recent shared activity, member summary,
type-distribution bars, private band); **Scope browser** (the core: left pane with global +
project + archived groups **and the persistent, non-enterable private-guarantee panel**;
entries **table and cards** layouts; type filter; search; sortable columns; loading skeleton;
sync-error + retry; empty; archived read-only); Team & users (roles, status active/invited/
disabled, invite flow); Settings (write-scope policy, who-may-create-scopes, connector details
with copy + rotate, the private "locked" section); Account (profile, password, MFA, passkey,
sessions — wrap the backend's account endpoints or link out, per the backend); side-panel
editors (entry / user / scope); confirm modal; toasts; theme toggle; collapsible rail.
Light + dark. Keyboard/focus/a11y (`aria-sort`, focus-on-open, visible focus rings).

## The private invariant in the UI — preserve all of it
Keep **all five** private-guarantee surfaces from the prototype (scope-browser panel,
dashboard band, settings locked section, account panel, team/invite copy). The console must
have **no** code path that displays private memory.

## Deliverables & structure
`frontend/` Next.js app; add it to `docker-compose.yml`; Caddy routing for the console plus
the `/mcp` and auth hostnames. A thin typed API client against the backend contract (§D) — if
the backend isn't ready, mock behind that client so the swap is trivial. `README.md`.

## Discipline
Produce a build plan + component/route map first and **wait for my go** before scaffolding.
Match the prototype's look; deviate only with a stated reason. Do not invent decisions — ask
or record an ADR. English artifacts. Apache-2.0.
