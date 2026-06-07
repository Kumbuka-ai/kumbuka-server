# ADR-0012: production deploy topology — share the host Caddy, pull images from GHCR

- Status: Accepted
- Date: 2026-06-07

## Context

The first kumbuka deployment goes onto a server that already runs Docker
with a Caddy container, fronting other services (n8n, others). The
operator wants a GitOps-flavoured flow: tag → CI builds → server pulls
→ healthcheck → rollback-on-fail. They do NOT want a second Caddy
parallel to the existing one.

Two questions this ADR answers:

1. How does the kumbuka stack relate to the existing reverse proxy?
2. Where do the images come from, and how does a tag turn into a
   running deployment?

## Decision

### One Caddy on the host. kumbuka rides on its network.

The production stack ships a `docker-compose.prod.yml` with **no Caddy
service**. The existing host Caddy is the only TLS terminator and
reverse proxy. kumbuka's containers join the same docker network as
that Caddy (the network name is host-specific, set via `CADDY_NETWORK`
in `.env`).

To wire kumbuka into the host Caddy, the deploy bundle ships a single
Caddyfile snippet at `deploy/caddy/kumbuka.caddy`. The operator drops it
under the host Caddy's site-snippet directory and adds a one-liner
`import /path/to/kumbuka.caddy` to the host Caddyfile. The snippet has
no global `{ ... }` block — that stays the host's concern.

Two vhosts in the snippet:

- `${KUMBUKA_DOMAIN}` → routes `/api/*`, `/mcp`, `/.well-known/*` to
  `kumbuka-backend:8080` and everything else to `kumbuka-console:3000`.
- `${KC_HOSTNAME}` → routes everything to `kumbuka-keycloak:8080`.

Both `kumbuka-backend` and `kumbuka-keycloak` get `X-Forwarded-Host` and
`X-Forwarded-Proto: https` set up so Quarkus' OIDC redirect URLs and
Keycloak's hostname resolution come out right.

### Images come from GHCR, tag-pinned.

CI (`.github/workflows/release.yml`) builds three images on every
`v*.*.*` tag and pushes them to GHCR:

```
ghcr.io/kumbuka-ai/kumbuka-backend:{tag, version, latest}
ghcr.io/kumbuka-ai/kumbuka-keycloak:{tag, version, latest}
ghcr.io/kumbuka-ai/kumbuka-console:{tag, version, latest}
```

(`kumbuka-console` is built by the sibling `kumbuka-console` repo's
release workflow.)

The host pulls by **explicit tag**, not by `:latest`. The tag lives in
the `KUMBUKA_VERSION` environment variable, which `docker-compose.prod.yml`
interpolates into the `image:` lines. The deploy script flips the
variable, runs `docker compose pull`, then `up -d`, then healthchecks.

GHCR auth on the host uses a single Personal Access Token with
`read:packages` scope (plus `repo` if the source repos are private and
the host wants to clone them). The token lives at `/etc/kumbuka/ghcr.pat`
with `chmod 600` and is fed to `docker login` via `--password-stdin`
inside the deploy script.

### Rollback is image-rollback, not schema-rollback.

The deploy script writes the outgoing `KUMBUKA_VERSION` into a state
file before flipping. On healthcheck failure, the rollback path is:
restore the previous value in `.env`, `compose pull`, `compose up -d`,
healthcheck again. Schema is intentionally **not rolled back**: we keep
forward-only Flyway migrations and write code that is N–1 compatible
(new columns are nullable, old columns are deprecated for at least one
release before drop). When that discipline isn't enough, the operator
restores from the pre-deploy `pg_dump` the script took just before
flipping — manual step, by design, because schema rollback under load
is never safe to automate.

## Consequences

- The deploy stack inherits whatever TLS and cert-renewal posture the
  host Caddy already has. We don't double-manage certs.
- The kumbuka containers are reachable from inside the host's docker
  network. If anything else on that network shouldn't be able to talk
  to them, that's a per-service firewall concern (Caddy still has to
  reach them by container name, which means open inside the network).
- Production never bind-mounts the Keycloak theme — the
  `kumbuka-keycloak` image bakes it in via `keycloak/Dockerfile`. The
  dev `docker-compose.yml` continues to use a bind mount + theme
  caching disabled for hot-reload during development.
- Schema rollback is **manual**. Operators who need automated schema
  rollback should reconsider the deploy cadence — every-tag-rollback is
  rarely safe in any system with a schema.
- The pull-deploy is initiated from the host (n8n workflow → deploy.sh
  → docker compose). GitHub Actions doesn't push to the server; the
  server pulls. This keeps GHA's permission surface minimal (GHCR-write
  for images, nothing else).
