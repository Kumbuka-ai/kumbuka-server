#!/bin/bash
# ---------------------------------------------------------------------------
# Postgres init script — runs once on first container start.
# Creates two databases (keycloak, kumbuka) and dedicated app users for each,
# creates the four roles the migration chain grants to, and pins the kumbuka
# app user's search_path to `platform, public`.
#
# WHY THE FOUR ROLES ARE CREATED HERE AND NOT BY THE CHAIN
#
# The chain wants to create them itself — V6 `kumbuka_ops_reader`, V21
# `kumbuka_worklist` and `kumbuka_logbook`, V22 `kumbuka_memory` — and each of
# those sits in an `IF NOT EXISTS` block, so creating them first turns those
# blocks into no-ops. That is not a nicety: the app user CANNOT create them.
#
# Measured 2026-09-20 against PostgreSQL 16, with the role exactly as this
# script creates it (rolsuper=f, rolcreaterole=f, rolbypassrls=f):
#
#   ERROR:  permission denied to create role
#   DETAIL: Only roles with the CREATEROLE attribute may create roles.
#
# — and with CREATEROLE added, V6 still fails, because `kumbuka_ops_reader`
# carries BYPASSRLS and only a role that has it may hand it out.
#
# Giving the app user CREATEROLE and BYPASSRLS would fix the boot and break the
# product: BYPASSRLS on the RUNTIME role switches off row-level security for
# the application itself, which is the tenant isolation. So the privileged act
# stays here, with the superuser this script already runs as, and the app user
# keeps the smallest set of attributes it can have.
#
# The placeholder passwords are the ones the migrations would have used; a
# deployment replaces them with `ALTER ROLE … PASSWORD …` exactly as before.
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
# THIS SCRIPT DOES NOT FINISH THE INSTALLATION — ONE STEP FOLLOWS IT
#
# It used to claim that a self-hoster "gets the setting for free on a fresh
# install". That was wrong, and the way it was wrong cost a restart.
#
# The pinned search_path above means `current_schema()` is `public` while
# `platform` does not exist yet and `platform` from the moment V21 creates it.
# Flyway, configured with neither `schemas` nor `defaultSchema`, keeps its
# history in `current_schema()`. So the first boot puts the history in `public`
# and every later boot looks for it in `platform`. Measured 2026-09-20:
#
#   boot 1: Schema history table "public"."flyway_schema_history" does not exist yet
#           RESULT migrate OK executed=22
#   boot 2: Schema history table "platform"."flyway_schema_history" does not exist yet
#           FlywayException: Found non-empty schema(s) "platform" but no schema
#           history table.
#
# With `baseline-on-migrate` off — and it is off deliberately, so that a half
# state is loud — that second boot is a hard refusal. A fresh installation
# therefore starts exactly once until the history is moved.
#
# Moving it is what `deploy/upgrade/finish-fresh-install.sh` does, and running
# it ONCE after the first successful start is part of installing, not an
# upgrade step. It is idempotent, so running it again is safe. See
# `deploy/upgrade/README.md`.
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

	-- The chain's grantees. Plain CREATE ROLE, not IF NOT EXISTS: this script
	-- runs once, on an empty data directory, where none of them can exist yet.
	CREATE ROLE kumbuka_ops_reader LOGIN BYPASSRLS PASSWORD 'change-me-kumbuka-ops-reader';
	CREATE ROLE kumbuka_worklist   LOGIN PASSWORD 'change-me-kumbuka-worklist';
	CREATE ROLE kumbuka_logbook    LOGIN PASSWORD 'change-me-kumbuka-logbook';
	CREATE ROLE kumbuka_memory     LOGIN PASSWORD 'change-me-kumbuka-memory';
EOSQL
