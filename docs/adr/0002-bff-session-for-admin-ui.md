# ADR-0002: BFF session cookie for the admin UI

- Status: Accepted
- Date: 2026-06-05

## Context

The Next.js admin UI needs authenticated access to admin APIs. Two common
patterns exist:

1. **SPA-with-tokens**: the browser runs the OAuth flow against Keycloak,
   stores the access token (memory, sessionStorage, or worker), and attaches
   it to API calls.
2. **BFF (Backend for Frontend)**: the browser only ever talks to its own
   backend. The backend runs the OAuth flow, keeps the tokens server-side,
   and issues an HttpOnly session cookie to the browser.

The spec mandates the BFF pattern: "the frontend NEVER talks to Keycloak
directly and NEVER holds tokens". The reasoning is well-established — XSS
cannot exfiltrate tokens that don't exist in JS-readable storage, the access
token never enters the browser's address bar / dev tools / fetch metadata,
and refresh-token handling stays out of the browser entirely.

## Decision

- The admin UI authenticates via Keycloak's authorization code flow handled
  **by the Quarkus backend** using `quarkus-oidc` in `web-app` mode with the
  confidential client `kumbuka-admin`.
- Login flow: browser hits `/api/auth/login` on the backend → backend
  redirects to Keycloak → Keycloak redirects to `/api/auth/callback` on the
  backend → backend exchanges code for tokens, stores them server-side,
  issues an HttpOnly, Secure, SameSite=Lax session cookie.
- Token storage strategy: `quarkus.oidc.token-state-manager.strategy=keep-all-tokens`,
  encrypted at rest in the cookie value (Quarkus default) — no external
  session store needed for the dev scaffold. If we scale the backend
  horizontally, revisit this with a Redis-backed token store.
- The session cookie is the only credential the frontend handles. The
  frontend has zero knowledge of OAuth.
- Logout clears the session cookie and triggers RP-initiated logout at
  Keycloak.

## Consequences

- One Quarkus app, two OIDC tenants: `mcp` (bearer / resource server) and
  `admin` (web-app / BFF). `OidcTenantResolver` selects based on path.
- The admin UI is **only** reachable via the same origin as the backend,
  because cookies are scoped to the origin Caddy serves. No CORS gymnastics.
- The MCP endpoint is unaffected — it remains a stateless bearer endpoint for
  external MCP clients (claude.ai, Claude Desktop, etc.).
- If we ever need to embed the admin UI cross-origin, this ADR would need
  superseding (CSRF and SameSite concerns change).
