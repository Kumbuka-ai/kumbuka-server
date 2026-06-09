#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# backup.sh — standalone scheduled DB backup (separate from deploy-time
# snapshots). Dumps both databases in custom format (pg_restore --list-able)
# into ~/kumbuka/backups/scheduled-<UTC>.{kumbuka,keycloak}.dump.
# Retention: 14 days. Intended to run from a systemd timer / cron every 6h.
# AGPL-3.0.
# ---------------------------------------------------------------------------
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

load_env
postgres_running || die "kumbuka-postgres is not running; nothing to back up"

mkdir -p "$BACKUP_DIR"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
base="$BACKUP_DIR/scheduled-${ts}"
log "scheduled backup -> ${base}.{kumbuka,keycloak}.dump"
dump_db "$KUMBUKA_DB_NAME"  "${base}.kumbuka.dump"
dump_db "$KEYCLOAK_DB_NAME" "${base}.keycloak.dump"

# Retention: delete scheduled dumps older than 14 days.
find "$BACKUP_DIR" -maxdepth 1 -name 'scheduled-*.dump' -type f -mtime +14 -print -delete

log "scheduled backup done"
