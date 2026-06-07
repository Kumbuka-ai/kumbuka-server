#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# deploy.sh vX.Y.Z — pull a GHCR-tagged release and roll it out, with a
# pre-deploy DB snapshot and automatic rollback on healthcheck failure.
# Lockfile-guarded and idempotent. Apache-2.0.
#
# See ../README.md for the full runbook.
# ---------------------------------------------------------------------------
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

[ "$#" -eq 1 ] || die "usage: deploy.sh vX.Y.Z"
NEW="$1"

# --- single-flight lock --------------------------------------------------
exec 9>/var/lock/kumbuka-deploy.lock
flock -n 9 || die "another deploy is already running (lock held: /var/lock/kumbuka-deploy.lock)"

load_env
OLD="${KUMBUKA_VERSION:-}"
[ -n "$OLD" ] || die "KUMBUKA_VERSION not set in .env"
log "=== deploy start: OLD=$OLD NEW=$NEW ==="

# First boot? (no running postgres yet → nothing to snapshot, full up.)
if postgres_running; then FIRST_BOOT=0; else FIRST_BOOT=1; fi

ghcr_login

# --- pre-deploy DB snapshot (skip on first boot) -------------------------
mkdir -p "$BACKUP_DIR"
if [ "$FIRST_BOOT" = 0 ]; then
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  snap="$BACKUP_DIR/pre-deploy-${ts}-${OLD}"
  log "pre-deploy snapshot -> ${snap}.{kumbuka,keycloak}.dump"
  dump_db "$KUMBUKA_DB_NAME"  "${snap}.kumbuka.dump"
  dump_db "$KEYCLOAK_DB_NAME" "${snap}.keycloak.dump"
  # Retain the last 10 pre-deploy snapshots (per database).
  for pat in kumbuka keycloak; do
    ls -1t "$BACKUP_DIR"/pre-deploy-*."$pat".dump 2>/dev/null | tail -n +11 | xargs -r rm -f
  done
else
  log "first boot: no running stack to snapshot, skipping pre-deploy dump"
fi

# --- flip the version ----------------------------------------------------
set_env KUMBUKA_PREVIOUS_VERSION "$OLD"
set_env KUMBUKA_VERSION "$NEW"
export KUMBUKA_VERSION="$NEW"   # shell env overrides .env for compose; keep them in sync
log "version flipped: KUMBUKA_VERSION=$NEW (previous=$OLD)"

# --- pull + up -----------------------------------------------------------
log "docker compose pull"
"${COMPOSE[@]}" pull

# NOTE: tolerate a non-zero `up` (e.g. compose aborts when a service stays
# unhealthy and a dependant has `depends_on: service_healthy`). We must NOT let
# set -e kill the script here — the healthcheck below is the decision point and
# must run so a bad release rolls back instead of leaving the script dead.
if [ "$FIRST_BOOT" = 1 ]; then
  log "first boot: bringing up the full stack"
  "${COMPOSE[@]}" up -d || log "compose up reported errors — proceeding to healthcheck"
else
  log "rolling update (postgres untouched)"
  "${COMPOSE[@]}" up -d --no-deps kumbuka-keycloak kumbuka-backend kumbuka-console \
    || log "compose up reported errors — proceeding to healthcheck"
fi

# --- healthchecks --------------------------------------------------------
# Containers first (first boot needs time for realm import + Flyway), then the
# public HTTPS surface (spec gate: 60s / 2s).
if wait_containers_healthy 180 && public_healthcheck 60; then
  log "=== deployed $NEW ==="
  exit 0
fi

# --- failure -> rollback -------------------------------------------------
log "healthcheck failed for $NEW — invoking rollback"
if "$(dirname "${BASH_SOURCE[0]}")/rollback.sh"; then
  die "deploy of $NEW failed; rolled back to $OLD"
else
  die "deploy of $NEW failed AND rollback failed — manual intervention required (see backups in $BACKUP_DIR)"
fi
