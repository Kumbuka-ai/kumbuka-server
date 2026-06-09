#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# rollback.sh [vX.Y.Z] — roll the app services back to a previous image tag.
# Default tag: KUMBUKA_PREVIOUS_VERSION from .env. Schema is NOT rolled back
# (forward-only Flyway, N-1 compatible — see ADR-0012); for schema issues,
# restore the pre-deploy pg_dump manually. Apache-2.0.
# ---------------------------------------------------------------------------
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

# Own the lock if invoked directly; if called from deploy.sh (which holds it),
# flock on the same fd is re-entrant within the same process tree via a fresh fd.
exec 8>/var/lock/kumbuka-deploy.lock
flock -n 8 || true   # deploy.sh may already hold it; proceed in that case.

load_env
TAG="${1:-${KUMBUKA_PREVIOUS_VERSION:-}}"
[[ -n "$TAG" ]] || die "no rollback tag given and KUMBUKA_PREVIOUS_VERSION is empty"
log "=== rollback to $TAG ==="

set_env KUMBUKA_VERSION "$TAG"
export KUMBUKA_VERSION="$TAG"

ghcr_login
log "docker compose pull"
"${COMPOSE[@]}" pull
log "bringing up app services at $TAG (postgres untouched)"
# Tolerate a non-zero up (see deploy.sh) — the healthcheck below is the gate.
"${COMPOSE[@]}" up -d --no-deps kumbuka-keycloak kumbuka-backend kumbuka-console \
  || log "compose up reported errors — proceeding to healthcheck"

if wait_containers_healthy 180 && public_healthcheck 60; then
  log "=== rolled back to $TAG ==="
  exit 0
fi
die "rollback to $TAG did not become healthy — manual intervention required"
