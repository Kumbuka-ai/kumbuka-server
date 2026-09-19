#!/bin/bash
# ---------------------------------------------------------------------------
# Postgres init script — runs once on first container start.
# Creates two databases (keycloak, kumbuka) and dedicated app users for each,
# and pins the kumbuka app user's search_path to `platform, public`.
#
# WHY THE SEARCH_PATH IS SET HERE, AT CREATION
#
# Since the tenancy inventory moved into the schema `platform`, the runtime
# role no longer finds its own tables under the default `"$user", public`. The
# path belongs on the ROLE and never in the image: an application property
# would travel with the container, so an older image rolled back onto this
# database would carry the wrong one, while a setting on the role is a property
# of the installation and is correct for every image that connects. `public`
# stays on the path behind `platform`, because the memory tables are still
# there and pgcrypto's functions always will be.
#
# This is the CE installation path, so it is the place a self-hoster gets the
# setting for free on a fresh install. Existing installations get the same
# setting from the upgrade script under the server's deploy/upgrade/.
# ---------------------------------------------------------------------------
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
	CREATE USER ${KEYCLOAK_DB_USER} WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
	CREATE DATABASE ${KEYCLOAK_DB_NAME} OWNER ${KEYCLOAK_DB_USER};
	GRANT ALL PRIVILEGES ON DATABASE ${KEYCLOAK_DB_NAME} TO ${KEYCLOAK_DB_USER};

	CREATE USER ${KUMBUKA_DB_USER} WITH PASSWORD '${KUMBUKA_DB_PASSWORD}';
	CREATE DATABASE ${KUMBUKA_DB_NAME} OWNER ${KUMBUKA_DB_USER};
	GRANT ALL PRIVILEGES ON DATABASE ${KUMBUKA_DB_NAME} TO ${KUMBUKA_DB_USER};
	ALTER ROLE ${KUMBUKA_DB_USER} SET search_path = platform, public;
EOSQL
