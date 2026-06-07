# Keycloak realm import

`kumbuka-realm.json` is imported on first start of the Keycloak container via
`start-dev --import-realm`. It defines:

## Realm `kumbuka`

- `sslRequired: external` — TLS is terminated by Caddy; the inter-container
  hop to Keycloak is plain HTTP and that's fine for local/internal traffic.
- `registrationAllowed: false` — users are provisioned by the admin via the
  backend, never self-service.
- `bruteForceProtected: true`.
- Refresh-token rotation **on** (`revokeRefreshToken: true`,
  `refreshTokenMaxReuse: 0`) — required by the OAuth 2.1 best-practices that
  MCP clients expect.

## Roles

- `member` — assigned to all users via `default-roles-kumbuka` composite.
  Read shared scopes, read/write own private scope.
- `admin` — assigned to designated admins on top of `member`. Manage shared
  scopes, manage team members, **cannot** read private memories.

## Clients

| Client            | Type              | Auth flow             | Used by             |
| ----------------- | ----------------- | --------------------- | ------------------- |
| `kumbuka-connector`| Confidential + PKCE S256 | Authorization code | claude.ai / Desktop |
| `kumbuka-admin`    | Confidential web  | Authorization code    | Backend BFF (admin) |
| `kumbuka-backend`  | Confidential SA   | Client credentials    | Backend → Admin API |

> `kumbuka-connector` requires BOTH a client secret AND PKCE S256 at token
> exchange — see ADR-0006. claude.ai is pre-registered with the secret.

### `kumbuka-connector` redirect URIs

Pre-configured for:

- `https://claude.ai/api/mcp/auth_callback`
- `https://claude.com/api/mcp/auth_callback`
- `http://127.0.0.1/*` (Claude Desktop loopback)
- `http://localhost/*` (Claude Desktop loopback, alt)

If claude.ai changes its callback path, or you want to test with **MCP
Inspector** (which uses `http://localhost:6274/oauth/callback` or similar),
add the URI to this list. The realm import only runs on **first** boot;
after that, edit via the Admin REST API or via the `kc.sh` CLI inside the
container (`docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...`).

### Audience binding

The `kumbuka-connector` client carries an `oidc-audience-mapper` that places
`kumbuka-connector` into the access token's `aud` claim. The backend
validates `aud == kumbuka-connector` on every `/mcp` request — this matches
the MCP / OAuth 2.1 expectation that bearer tokens are bound to the resource
they were issued for.

## Test users

Created on first boot for development only — delete or disable before any
non-local deployment.

| Username        | Password | Roles            |
| --------------- | -------- | ---------------- |
| `admin@local`   | `admin`  | `admin`, `member`|
| `member@local`  | `member` | `member`         |

## Client secrets

The realm file ships with placeholder secrets matching the values in
`.env.example`. If you change `KUMBUKA_ADMIN_CLIENT_SECRET`,
`KUMBUKA_BACKEND_CLIENT_SECRET`, or `KUMBUKA_CONNECTOR_CLIENT_SECRET` in
`.env`, you must also rotate them in Keycloak — either by editing this
file **before first boot** or via `kcadm.sh update clients/<id> -s
'secret=...'` afterwards. The values must match or token validation /
issuance will fail.

> Keycloak realm imports do **not** support environment-variable
> substitution. This is a known limitation; the operational workaround is
> documented here so secret drift between `.env` and the realm is visible.

## First-boot sanity check

After `just infra`:

```bash
# OpenID Connect discovery
curl -fsS https://dev.kumbuka.ai/auth/realms/kumbuka/.well-known/openid-configuration | jq .

# Verify PKCE S256 is advertised
curl -fsS https://dev.kumbuka.ai/auth/realms/kumbuka/.well-known/openid-configuration \
  | jq '.code_challenge_methods_supported'
# expected: ["plain","S256"]
```
