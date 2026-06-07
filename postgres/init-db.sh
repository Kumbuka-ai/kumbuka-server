#!/bin/bash
# ---------------------------------------------------------------------------
# Postgres init script — runs once on first container start.
# Creates two databases (keycloak, kumbuka) and dedicated app users for each.
# ---------------------------------------------------------------------------
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
	CREATE USER ${KEYCLOAK_DB_USER} WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
	CREATE DATABASE ${KEYCLOAK_DB_NAME} OWNER ${KEYCLOAK_DB_USER};
	GRANT ALL PRIVILEGES ON DATABASE ${KEYCLOAK_DB_NAME} TO ${KEYCLOAK_DB_USER};

	CREATE USER ${KUMBUKA_DB_USER} WITH PASSWORD '${KUMBUKA_DB_PASSWORD}';
	CREATE DATABASE ${KUMBUKA_DB_NAME} OWNER ${KUMBUKA_DB_USER};
	GRANT ALL PRIVILEGES ON DATABASE ${KUMBUKA_DB_NAME} TO ${KUMBUKA_DB_USER};
EOSQL
