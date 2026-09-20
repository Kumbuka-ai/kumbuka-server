#!/usr/bin/env bash
# ===========================================================================
# finish-fresh-install.sh — the one step that finishes a FRESH installation.
#
# Run it ONCE, after the first successful start. It is idempotent: a second
# run reports that there is nothing to do and changes nothing.
#
# WHY A FRESH INSTALLATION NEEDS A STEP AT ALL
#
# The app role's search_path is pinned to `platform, public` at creation
# (init-db.sh). `current_schema()` is therefore `public` while `platform` does
# not exist and `platform` from the moment V21 creates it — and Flyway, given
# neither `schemas` nor `defaultSchema`, keeps its history in
# `current_schema()`. So the first boot writes the history into `public` and
# every later boot looks for it in `platform`. Measured 2026-09-20 against
# PostgreSQL 16 and Flyway 12.0.0:
#
#   boot 1  RESULT migrate OK executed=22       (history created in "public")
#   boot 2  FlywayException: Found non-empty schema(s) "platform" but no
#           schema history table.
#
# `baseline-on-migrate` is off on purpose, so that refusal is loud rather than
# a silently invented second history. What it costs is this step.
#
# It cannot be done earlier. `platform` does not exist before V21, so the
# history has nowhere to go on the first boot; and it cannot be done by a
# migration, because Flyway holds an AccessShareLock on its own history table
# for the whole run — an `ALTER TABLE ... SET SCHEMA` inside a migration waits
# on Flyway itself, forever, with the shipped `lock_timeout = 0`.
#
# WHAT IT ACTUALLY DOES
#
# It runs `stage-f-relocate-history.sql`, which moves the history into
# `platform` and pins the two search_paths in ONE transaction. That file is
# also the upgrade step for an EXISTING installation; this wrapper exists so a
# fresh install is one named command rather than a psql invocation whose three
# `-v` parameters have to be got right. The refusals are the SQL file's own.
#
# Usage
#   ./finish-fresh-install.sh                     # defaults below
#   KUMBUKA_DB_NAME=kumbuka_prod ./finish-fresh-install.sh
#   PSQL="docker exec -i kumbuka-postgres psql" ./finish-fresh-install.sh
#
# Environment (all optional)
#   KUMBUKA_DB_NAME   database                       (default: kumbuka)
#   KUMBUKA_DB_USER   the app role — migrator AND runtime in a CE install
#                                                    (default: kumbuka)
#   POSTGRES_USER     the role this script connects as (default: KUMBUKA_DB_USER)
#   PSQL              how to reach psql              (default: psql)
#
# The file is fed on STDIN rather than with -f, so that a PSQL that runs inside
# a container (`docker exec -i …`) reads the script from the HOST, where it
# lives, instead of needing it on the container's filesystem.
# ===========================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELOCATE_SQL="${RELOCATE_SQL:-$HERE/stage-f-relocate-history.sql}"

DB="${KUMBUKA_DB_NAME:-kumbuka}"
APP_ROLE="${KUMBUKA_DB_USER:-kumbuka}"
CONNECT_AS="${POSTGRES_USER:-$APP_ROLE}"
PSQL="${PSQL:-psql}"

[[ -f "$RELOCATE_SQL" ]] || { echo "finish-fresh-install: not found: $RELOCATE_SQL" >&2; exit 1; }

echo "finish-fresh-install: database=$DB app-role=$APP_ROLE connecting-as=$CONNECT_AS"

# The CE installation has ONE role: it migrates and it runs. Both `migrator`
# and `runtime` are therefore the same name, which is exactly the shape the
# relocation's two ALTER ROLE statements need — one scoped IN DATABASE, one
# global. A deployment with a separate migrator passes its own values.
exec $PSQL -v ON_ERROR_STOP=1 \
     --username "$CONNECT_AS" \
     --dbname "$DB" \
     -v migrator="${STAGE_F_MIGRATOR:-$APP_ROLE}" \
     -v runtime="${STAGE_F_RUNTIME:-$APP_ROLE}" \
     -v db="$DB" \
     < "$RELOCATE_SQL"
