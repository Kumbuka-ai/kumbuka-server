# ADR-0006: Connector OAuth client = confidential + PKCE

- Status: Accepted
- Date: 2026-06-05
- Decision-ref: D1 (ratified by JBA, 2026-06-05)

## Context

The MCP connector client (used by claude.ai / Claude Desktop / Claude Mobile
to obtain a bearer token for `/mcp`) had to choose between three OAuth
client types:

- **(A) Public client + PKCE S256** — no secret, claude.ai handles the
  PKCE code-verifier. Matches the OAuth 2.1 default recommendation for
  user-agent-based clients. Was the original scaffold choice (ADR-0004
  era).
- **(B) Confidential client + PKCE S256** — secret AND PKCE both required
  at token exchange. claude.ai must be pre-registered with the secret.
- **(C) Confidential client (no PKCE)** — secret only.

The console design (handoff §A, prototype) carries a Connector card with
endpoint + `client_id` + masked `client_secret` + a rotate action — i.e.
the operator expects to see and manage a real secret. With (A) there is no
secret, so the Connector card becomes display-only and the rotate action
is meaningless.

## Decision

Use **(B): confidential client + PKCE S256**.

- The Keycloak client `kumbuka-connector` is configured with
  `publicClient: false`, `clientAuthenticatorType: client-secret`, plus
  `pkce.code.challenge.method: S256`.
- claude.ai is pre-registered with `client_id` + `client_secret` for this
  server (the value-prop of explicit registration is that the connector
  has a real kill-switch and avoids the DCR-driven client-spam pattern
  some servers exhibit).
- The Backend continues to validate **only** the bearer token's signature
  and `aud == kumbuka-connector`. It never holds the connector secret —
  Keycloak is the source of truth and the secret is rotated through
  Keycloak's Admin REST API.
- The Connector card in the admin UI shows `client_id` + masked secret
  (last 4 chars) + a Rotate button. Rotate calls
  `POST /api/connector/secret:rotate` → backend → Keycloak `regenerateSecret`.
  The old secret is invalidated immediately.

## Fallback

If claude.ai rejects a confidential connector for this MCP server (e.g.
its OAuth client implementation requires public clients), revisit and
fall back to (A). At that point the Connector card's secret field becomes
display-only ("not applicable — PKCE only") and the Rotate action is
removed; client_id stays as the only meaningful credential.

This fallback would supersede this ADR; the connector card's behaviour
shifts at the same time.

## Consequences

- The realm import must ship a placeholder secret for `kumbuka-connector`
  in `kumbuka-realm.json` — operators rotate it before any non-local
  deployment.
- `.env.example` carries `KUMBUKA_CONNECTOR_CLIENT_SECRET` so the secret
  in `.env` and in the realm import stay aligned during initial bring-up
  (Keycloak realm imports do not support env-var substitution).
- The MCP audience claim, the Quarkus OIDC tenant configuration, and the
  Connector card all settle on a single client id: `kumbuka-connector`.
- PKCE remains mandatory — defence-in-depth, even though the secret is
  present.
