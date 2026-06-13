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
  # Parse .env as KEY=VALUE WITHOUT shell-sourcing it. Values may contain shell
  # metacharacters (e.g. the MCP url template's literal <alias>), which `. .env`
  # tries to EXECUTE -- the historical `alias: No such file or directory` crash
  # that silently broke the scheduled backups. export "$key=$val" sets the value
  # verbatim (the <,>,&,? are inert inside the quoted assignment argument).
  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    case "$line" in ''|\#*) continue;; *) ;; esac
    case "$line" in *=*) ;; *) continue;; esac
    key="${line%%=*}"
    val="${line#*=}"
    case "$key" in [A-Za-z_][A-Za-z0-9_]*) ;; *) continue;; esac
    export "$key=$val"
  done < "$ENVFILE"
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

# org_mapper_check — assert the kumbuka-admin client still emits the
# `organization` claim (sourced from the user's tenant_alias attribute). This is
# the exact config whose loss/tweak caused the 2026-06-13 incident: without it
# the RequestAwareTenantResolver rejects every console request as
# TOKEN_ORG_MISSING. Read-only: mints a client_credentials token with the
# kumbuka-backend service account (has view-clients) and inspects the
# kumbuka-admin protocol mappers via the Admin REST API. No writes.
org_mapper_check() {
  command -v jq >/dev/null 2>&1 || { log "org_mapper_check: jq not on PATH"; return 1; }
  local base="https://${KC_HOSTNAME}" realm="${KUMBUKA_REALM:-kumbuka}" tok cid n
  [ -n "${KUMBUKA_BACKEND_CLIENT_SECRET:-}" ] || { log "org_mapper_check: KUMBUKA_BACKEND_CLIENT_SECRET unset"; return 1; }
  tok="$(curl -fsS -m 10 -X POST "$base/realms/$realm/protocol/openid-connect/token" \
    -d grant_type=client_credentials -d client_id=kumbuka-backend \
    --data-urlencode "client_secret=$KUMBUKA_BACKEND_CLIENT_SECRET" 2>/dev/null \
    | jq -r '.access_token // empty')" || true
  [ -n "$tok" ] || { log "org_mapper_check: could not mint admin token"; return 1; }
  cid="$(curl -fsS -m 10 -H "Authorization: Bearer $tok" \
    "$base/admin/realms/$realm/clients?clientId=kumbuka-admin" 2>/dev/null | jq -r '.[0].id // empty')" || true
  [ -n "$cid" ] || { log "org_mapper_check: kumbuka-admin client not found"; return 1; }
  n="$(curl -fsS -m 10 -H "Authorization: Bearer $tok" \
    "$base/admin/realms/$realm/clients/$cid/protocol-mappers/models" 2>/dev/null \
    | jq -r '[.[] | select(.config["claim.name"]=="organization" and .config["user.attribute"]=="tenant_alias")] | length')" || true
  if [ "${n:-0}" -ge 1 ]; then
    log "org_mapper_check OK -- kumbuka-admin emits 'organization' (from tenant_alias)"
    return 0
  fi
  log "org_mapper_check FAILED -- kumbuka-admin is MISSING the organization->tenant_alias mapper; tenant resolution would break (TOKEN_ORG_MISSING). Re-run keycloak/06-add-organization-mapper.sh"
  return 1
}
