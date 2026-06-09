#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Shared helpers for the kumbuka deploy scripts. Sourced, not executed.
# AGPL-3.0.
# ---------------------------------------------------------------------------
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENVFILE="$DEPLOY_DIR/.env"
BACKUP_DIR="${KUMBUKA_BACKUP_DIR:-$HOME/kumbuka/backups}"
COMPOSE=(docker compose -f "$DEPLOY_DIR/compose.prod.yml")

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

load_env() {
  [[ -f "$ENVFILE" ]] || die "missing $ENVFILE"
  set -a; . "$ENVFILE"; set +a
}

# set_env KEY VALUE — update KEY in .env in place (or append).
set_env() {
  local k="$1" v="$2"
  if grep -qE "^${k}=" "$ENVFILE"; then
    # '|' delimiter; values are versions/hex — no '|' inside.
    sed -i -E "s|^${k}=.*|${k}=${v}|" "$ENVFILE"
  else
    printf '%s=%s\n' "$k" "$v" >> "$ENVFILE"
  fi
}

ghcr_login() {
  [[ -f "$GHCR_PAT_FILE" ]] || die "missing GHCR PAT at $GHCR_PAT_FILE"
  log "docker login ghcr.io as $GHCR_USERNAME"
  docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin < "$GHCR_PAT_FILE" >/dev/null \
    || die "ghcr login failed"
}

# True if the kumbuka stack's postgres is already running.
postgres_running() {
  "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx kumbuka-postgres
}

# dump_db DBNAME OUTFILE — custom-format (-Fc) archive, pg_restore --list-able.
# Dumps as the Postgres SUPERUSER: the kumbuka schema uses FORCE ROW LEVEL
# SECURITY (ADR-0003/0011), so a dump as the table-owning app user is blocked by
# the RLS policy. Superusers bypass RLS, so this captures every row.
dump_db() {
  local db="$1" out="$2"
  "${COMPOSE[@]}" exec -T kumbuka-postgres pg_dump -U "$POSTGRES_USER" -Fc "$db" > "$out" \
    || die "pg_dump of $db failed"
  [[ -s "$out" ]] || die "pg_dump of $db produced an empty file"
}

# Wait until all kumbuka services with a healthcheck report healthy.
wait_containers_healthy() {
  local timeout="${1:-180}" svc cid st deadline
  deadline=$((SECONDS + timeout))
  log "waiting up to ${timeout}s for containers to become healthy"
  while :; do
    local all_ok=1
    for svc in kumbuka-postgres kumbuka-keycloak kumbuka-backend kumbuka-console; do
      cid="$("${COMPOSE[@]}" ps -q "$svc" 2>/dev/null || true)"
      if [[ -z "$cid" ]]; then all_ok=0; break; fi
      st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo missing)"
      if [[ "$st" != "healthy" ]]; then all_ok=0; break; fi
    done
    if [[ "$all_ok" = 1 ]]; then log "all containers healthy"; return 0; fi
    if [[ "$SECONDS" -ge "$deadline" ]]; then
      log "containers not healthy within ${timeout}s (last: $svc=$st)"
      "${COMPOSE[@]}" ps || true
      return 1
    fi
    sleep 3
  done
}

# One pass of the three public HTTPS healthchecks. Returns 0 only if all pass.
public_healthcheck_once() {
  curl -fsS -m 5 "https://${KUMBUKA_DOMAIN}/.well-known/oauth-protected-resource" 2>/dev/null \
    | grep -q authorization_servers || return 1
  curl -fsS -m 5 -o /dev/null "https://${CONSOLE_DOMAIN}/api/health" 2>/dev/null || return 1
  curl -fsS -m 5 -o /dev/null "https://${KC_HOSTNAME}/realms/kumbuka/.well-known/openid-configuration" 2>/dev/null || return 1
  return 0
}

# Loop the public healthcheck (spec: 60s timeout, 2s interval).
public_healthcheck() {
  local timeout="${1:-60}" deadline
  deadline=$((SECONDS + timeout))
  log "public healthcheck (<=${timeout}s): protected-resource@${KUMBUKA_DOMAIN}, /api/health@${CONSOLE_DOMAIN}, openid-config@${KC_HOSTNAME}"
  while :; do
    if public_healthcheck_once; then log "public healthcheck OK"; return 0; fi
    if [[ "$SECONDS" -ge "$deadline" ]]; then log "public healthcheck FAILED within ${timeout}s"; return 1; fi
    sleep 2
  done
}
