# ADR-0009: BFF auth flow — login init, XHR-aware /me, callback

- Status: Accepted
- Date: 2026-06-06
- Refines: [ADR-0002](0002-bff-session-for-admin-ui.md)

## Context

ADR-0002 settled on the BFF pattern: the admin UI never talks to Keycloak
directly, and the Quarkus backend handles the authorization-code flow. It
did not, however, pin down two practical edges that the Next.js console
needs:

1. How does the **browser** kick off sign-in? The console is a Server
   Components app — it cannot mount a Quarkus-redirected route the way a
   single embedded SPA can; it needs an explicit URL that triggers the
   redirect on a top-level navigation.
2. How does **`/api/auth/me`** behave when the caller is unauthenticated?
   Quarkus' `web-app` tenant defaults to issuing a 302 to the OIDC
   authorization endpoint. That is fine for a top-level browser navigation,
   but breaks XHR / `fetch` calls in the console: `fetch` either silently
   follows the redirect (and CORS-fails on the Keycloak origin) or surfaces
   an opaque response with no way for the UI to render a "sign in" CTA.

The console's Server-Component renders need a deterministic, machine-readable
answer for both cases.

## Decision

Two thin endpoints + one behavior tweak on the existing `/api/auth/me`:

1. **`GET /api/auth/login?return_to=<path>`** — public, no auth. Triggers
   the Quarkus OIDC authorization-code redirect, then `return_to` after the
   callback (validated against an allowlist of same-origin paths to avoid
   open-redirect). Intended **only** for top-level browser navigation
   (`<a href>` or `window.location =`); never invoked from `fetch`.

2. **`/api/auth/me`** returns **401 JSON** (not a 302) when the request
   looks like an XHR — detected by `X-Requested-With: XMLHttpRequest` or
   `Accept: application/json` without `text/html`. The body includes
   `{ "loginUrl": "/api/auth/login?return_to=…" }` so the UI can render the
   sign-in CTA without hard-coding the path. Top-level browser navigations
   to `/api/auth/me` keep the default 302 behavior.

3. **`/api/auth/callback`** unchanged — exchanges the code, sets the
   HttpOnly session cookie, redirects to `return_to`.

4. **`/api/auth/logout`** unchanged — clears the session cookie and
   triggers RP-initiated logout at Keycloak.

The console uses the flow like this:

- Server Components call `serverFetch("/api/auth/me")`. On 401 they
  `redirect("/signin")`.
- `/signin` renders a "Sign in with Keycloak" anchor whose `href` is
  `/api/auth/login?return_to=<the original path>`. Clicking it is a
  top-level navigation; Quarkus handles the rest.
- After callback, the browser lands on `return_to` with a session cookie.

## Consequences

- The frontend never speaks OAuth and never holds tokens. It only ever sees
  a session cookie and a `loginUrl` string.
- `return_to` validation lives in the backend — the frontend cannot widen
  the allowlist by accident.
- The 401-vs-302 split on `/api/auth/me` is content-negotiated, so curl /
  HTMX / classic browser flows continue to work; only the XHR path changes.
- If we later add an in-app embedded login (we don't plan to), the contract
  stays the same — the embed would just hit `/api/auth/login` in an iframe.
- A future single-sign-out from the rail would also use `/api/auth/logout`
  as a top-level navigation, not a `fetch`.
