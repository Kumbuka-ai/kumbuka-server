# kumbuka — production deploy (operator runbook)

This directory is the **operator-side** deploy machinery for kumbuka on this
host. It is a pure *consumer* of release artifacts: images are pulled from GHCR
by tag; nothing here is pushed back to GitHub.

The reference clones the artifacts came from live read-only under
`~/kumbuka/src/{server,console}` and are not used at runtime.

## Topology (this host)

- One **host Caddy** (`jba-stack-caddy-1`) terminates TLS for everything and
  reverse-proxies kumbuka by container name. kumbuka ships **no Caddy** of its
  own; its containers join the host Caddy network `jba-stack_internal`
  (`CADDY_NETWORK` in `.env`). See ADR-0012.
- **Three public hosts** (operator's choice):
  | Host | Serves |
  |------|--------|
  | `console.kumbuka.ai` | admin console UI + `/api/*` (BFF) → backend |
  | `mcp.kumbuka.ai` | `/mcp` + `/.well-known/*` → backend (MCP resource) |
  | `auth.kumbuka.ai` | Keycloak |
  `KUMBUKA_DOMAIN` is the **MCP host** because the backend builds the OAuth
  protected-resource id as `https://<KUMBUKA_DOMAIN>/mcp`. The console host is
  `CONSOLE_DOMAIN`.

## Layout

```
compose.prod.yml          the stack (postgres, keycloak, backend, console)
.env                      secrets + config (chmod 600, never commit)
postgres/init-db.sh       creates the two DBs + app users on first boot
keycloak/realm-import/    realm seeded on Keycloak first boot
caddy/kumbuka.caddy       host-Caddy snippet (3 vhosts) — imported by the host Caddyfile
scripts/
  _lib.sh                 shared helpers
  deploy.sh               deploy a version (+ snapshot + auto-rollback)
  rollback.sh             roll app services back to a tag
  backup.sh               scheduled DB backup
```

## Deploy

```sh
sudo ./scripts/deploy.sh v0.1.0
```

What it does: takes a single-flight lock; on an existing stack takes a
pre-deploy `pg_dump` of both DBs into `~/kumbuka/backups/` (keeps the last 10);
records the outgoing version as `KUMBUKA_PREVIOUS_VERSION`; flips
`KUMBUKA_VERSION`; `docker login ghcr.io`; `compose pull`; brings the app
services up (`--no-deps`, postgres untouched — full `up -d` on first boot);
waits for container health, then runs the public HTTPS healthchecks:

- `https://mcp.kumbuka.ai/.well-known/oauth-protected-resource` → 200 + JSON `authorization_servers`
- `https://console.kumbuka.ai/api/health` → 200
- `https://auth.kumbuka.ai/realms/kumbuka/.well-known/openid-configuration` → 200

On success: prints `deployed <tag>`. On failure: runs `rollback.sh` and exits 1.

## Rollback

```sh
sudo ./scripts/rollback.sh            # back to KUMBUKA_PREVIOUS_VERSION
sudo ./scripts/rollback.sh v0.1.0     # back to an explicit tag
```

Image rollback only. **Schema is forward-only** (Flyway, N-1 compatible — see
ADR-0012). For a schema problem, restore the pre-deploy dump by hand:

```sh
gunzip </dev/null  # n/a — dumps are custom format:
"${COMPOSE}" exec -T kumbuka-postgres pg_restore -U kumbuka -d kumbuka --clean --if-exists \
  < ~/kumbuka/backups/pre-deploy-<ts>-<tag>.kumbuka.dump
```

## Backups

- **Deploy-time snapshots:** `~/kumbuka/backups/pre-deploy-<ts>-<tag>.{kumbuka,keycloak}.dump`, last 10 kept.
- **Scheduled:** `scripts/backup.sh` → `scheduled-<ts>.{kumbuka,keycloak}.dump`, 14-day retention, run by the `kumbuka-backup.timer` systemd timer every 6h.
- Both are **custom-format** (`pg_dump -Fc`) so `pg_restore --list <file>` works.

```sh
systemctl status kumbuka-backup.timer
sudo ./scripts/backup.sh               # run one now
```

## Logs & health

```sh
docker compose -f compose.prod.yml ps                       # health column
docker compose -f compose.prod.yml logs -f kumbuka-backend  # or -keycloak / -console / -postgres
```

## When a healthcheck fails

`deploy.sh` already auto-rolls-back. To investigate:

1. `docker compose -f compose.prod.yml ps` — which service is unhealthy?
2. `... logs <service>` — backend: DB/OIDC; keycloak: realm import / DB; console: backend URL.
3. Public layer: confirm DNS for the three hosts → this host, and that the host
   Caddy obtained certs (`docker logs jba-stack-caddy-1 | grep -i certificate`).

## Conventions & deviations from the shipped artifacts

- **`.sql.gz` → custom-format `.dump`.** The bootstrap brief named snapshots
  `*.sql.gz` *and* asked that `pg_restore --list` parse them — mutually
  exclusive (plain SQL is not a pg_restore archive). Resolved in favour of
  `pg_restore --list`: dumps are `pg_dump -Fc`.
- **postgres → `kumbuka-postgres`.** Avoids a network-alias clash with the
  n8n `postgres` already on `jba-stack_internal`.
- **3-vhost Caddy snippet** instead of the shipped 2-vhost (operator's split).
- **Realm:** dev test users removed; client secrets and redirect/origin hosts
  set for production. **SMTP = Gmail** (`smtp.gmail.com:587`, STARTTLS, user/from
  `info@kumbuka.ai`) — configured live on the running realm via the Admin REST API
  and seeded into `realm-import/kumbuka-realm.json` for fresh deploys. The Gmail
  **app password** lives at `/etc/kumbuka/smtp.pass` (also baked into the realm
  import). To rotate: update Google → write the new app password into both the
  realm's `smtpServer.password` (Admin API / console) and `realm-import`.

### Runtime fixes found while bringing v0.1.0 up

These work around bugs/gaps in the v0.1.0 artifacts. Each is a candidate to fix
upstream (in `kumbuka-server`) so it can be reverted here on a later release.

- **Keycloak `KC_HEALTH_ENABLED` removed.** It's a *build-time* option but the
  published keycloak image was baked (`kc.sh build`) without it; passing it at
  runtime under `start --optimized` aborts the boot (exit 2). Removed from the
  env; the keycloak healthcheck now probes the realm OIDC endpoint instead of
  `/health/ready` (which isn't served).
- **Backend healthcheck uses `wget`, not `/dev/tcp`.** The backend image's
  `/bin/sh` is busybox (no bash `/dev/tcp`), so the shipped probe always failed
  → container never healthy. The image ships `wget`.
- **DB dumps run as the Postgres superuser.** The `memory` table has FORCE ROW
  LEVEL SECURITY (ADR-0003/0011); `pg_dump` as the app/owner user is blocked by
  the policy. The superuser bypasses RLS.
- **Host Caddy carries network aliases** `auth/console/mcp.kumbuka.ai` on
  `jba-stack_internal` (in the jba-stack compose). Without them the backend
  can't reach its OIDC issuer `https://auth.kumbuka.ai` from inside the network
  (no hairpin to the host's public IP). With the alias, the hostname resolves to
  the Caddy container, which serves the valid LE cert.
- **Email logo via absolute URL (theme override).** The shipped email templates
  load the logo with `${url.resourcesUrl}/img/kumbuka-mark.png`, but Keycloak does
  not serve *email*-theme resources over HTTP → broken image in mail clients. The
  email theme is overridden via a bind-mount
  (`keycloak/email-theme-override` → `…/themes/kumbuka/email`) whose `<img>` points
  at the public PNG `https://www.kumbuka.ai/assets/email/kumbuka-mark.png` (hosted
  from the `kumbuka-web` repo). Upstream fix: bake an absolute image URL into the
  templates, then drop this mount.
- **deploy.sh / rollback.sh tolerate a non-zero `compose up`.** When a service
  stays unhealthy, `compose up` aborts (via a dependant's
  `depends_on: service_healthy`); under `set -e` that would kill the script
  before the healthcheck/rollback. The `up` failure is now logged and the
  healthcheck remains the decision point.

## First-time bootstrap (already done once)

GHCR PAT at `/etc/kumbuka/ghcr.pat` (chmod 600); host Caddy wired
(`import /etc/caddy/sites/*.caddy` + `KUMBUKA_DOMAIN`/`CONSOLE_DOMAIN`/`KC_HOSTNAME`
in the Caddy container env + the stack on `jba-stack_internal`); DNS for the
three hosts pointed at this server; then `./scripts/deploy.sh v0.1.0`.
