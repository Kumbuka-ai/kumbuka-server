#!/usr/bin/env bash
# ===========================================================================
# stage-f-probe.sh — witnesses the stage-F relocation against a throwaway
# Postgres, with a RED counter-probe for every claim it makes.
#
# It drives the real artefacts and never a stand-in: the migration chain out of
# this repository, the upgrade step from ../stage-f-relocate-history.sql, and
# flyway-core in exactly the version this module's build resolves. Nothing here
# touches a deployment; the container is created and destroyed per case.
#
# Cases
#   fresh        the chain V1..V23 on an empty database, then the upgrade step
#   prod         a database at the production state, upgraded the way a real
#                installation is: step first, new chain second
#   rollback     the previous chain (V1..V22) meeting an upgraded database
#   routing      team_tenant_id_by_alias across the move, both pins
#   catalogue    who may touch what in platform, after the move
#   revert       the way back: step, revert, and the old image starting again
#   revert-extra a role carrying another setting BEFORE the search_path — the
#                reset still happens and the other setting survives
#   reforward    step, revert, step — the second move is green
#
#   The CE shape (sprint/186.5) — ONE role, migrator and runtime at once, as
#   ops/postgres/init-db.sh creates it. The cases above all migrate as a
#   SUPERUSER with a separate runtime role, which is the ops-console shape;
#   that is why neither B1 nor B2 was visible here before.
#   fresh-ce     a fresh CE installation, the finishing step, three starts
#   revert-ce    relocate then revert where the runtime role OWNS platform
#   db-mismatch  both scripts refuse when db is not the connected database
#
# Red probes (each named RED, each expected to fail where a green run passes)
#   red-halfstate     history in both schemas — the step must refuse, unchanged
#   red-baseline      baseline-on-migrate on vs off in the half-state
#   red-catalogue     a granted SELECT on the view must turn the probe red
#   red-routing       the OLD pin must stop resolving once the tables move
#   red-revert-v23    after V23 the revert must refuse, unchanged
#   red-revert-path   a foreign search_path must stop the revert, unchanged —
#                     at index 1 and away from it
#   red-revert-window without the revert the old image cannot start; with it, it can
#   red-fresh-ce     without the finishing step a fresh CE install starts once
#   red-revert-ce    the OLD revert condition strips the schema owner
#   red-db-mismatch  without the db check the two halves come apart silently
#
# Usage
#   ./stage-f-probe.sh              # every case
#   ./stage-f-probe.sh fresh prod   # selected
#   KEEP=1 ./stage-f-probe.sh prod  # leave the container up
# ===========================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPGRADE_SQL="${UPGRADE_SQL:-$HERE/../stage-f-relocate-history.sql}"
REVERT_SQL="${REVERT_SQL:-$HERE/../stage-f-relocate-history-revert.sql}"
FINISH_SH="${FINISH_SH:-$HERE/../finish-fresh-install.sh}"
SERVER_ROOT="${SERVER_ROOT:-$(cd "$HERE/../../.." && pwd)}"
MIGRATIONS="${MIGRATIONS:-$SERVER_ROOT/backend/server/src/main/resources/db/migration}"
SERVER_POM="${SERVER_POM:-$SERVER_ROOT/backend/server/pom.xml}"
PG_IMAGE="${PG_IMAGE:-postgres:16}"
PORT="${PROBE_PORT:-55444}"
CT="${PROBE_CONTAINER:-kumbuka-stage-f-acceptance}"
DB=kumbuka
MIGRATOR=postgres
RUNTIME=kumbuka
# The single role of the CE shape — migrator and runtime at once.
CE_ROLE=kumbuka
KEEP="${KEEP:-}"

TENANT_A=11111111-1111-1111-1111-111111111111
TENANT_B=22222222-2222-2222-2222-222222222222

PASS=0; FAIL=0; GAP=0
WORK="$(mktemp -d "${TMPDIR:-/tmp}/stage-f-acceptance.XXXXXX")"
cleanup() { [[ -n "$KEEP" ]] || docker rm -f "$CT" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

hdr() { printf '\n========================================================================\n%s\n========================================================================\n' "$*"; }
say() { printf -- '--- %s\n' "$*"; }
ok()  { PASS=$((PASS+1)); printf 'ok     - %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf 'NOT OK - %s\n         %s\n' "$1" "${2:-}"; }
# A measured limitation that this stage cannot remove — reported loudly and
# never silently: it is a property of an ALREADY RELEASED image, so no change
# in this repository can make it go away. It is counted apart from a failure so
# that a real regression stays visible next to it.
gap() { GAP=$((GAP+1)); printf 'GAP    - %s\n         %s\n' "$1" "${2:-}"; }
die() { printf 'PROBE ABORTED: %s\n' "$*" >&2; exit 1; }

[[ -d "$MIGRATIONS" ]]   || die "migration directory not found: $MIGRATIONS"
[[ -f "$UPGRADE_SQL" ]]  || die "upgrade step not found: $UPGRADE_SQL"
[[ -f "$REVERT_SQL" ]]   || die "revert step not found: $REVERT_SQL"
[[ -x "$FINISH_SH" ]]    || die "finishing step not found or not executable: $FINISH_SH"
command -v docker >/dev/null || die "docker not on PATH"
command -v mvn    >/dev/null || die "mvn not on PATH"
command -v javac  >/dev/null || die "javac not on PATH"

# --- flyway of exactly the version this build resolves ----------------------
resolve_classpath() {
  mvn -B -q -f "$SERVER_POM" dependency:build-classpath \
      -Dmdep.includeScope=test -Dmdep.outputFile="$WORK/cp.txt" >/dev/null \
    || die "dependency:build-classpath failed"
  tr ':' '\n' < "$WORK/cp.txt" \
    | grep -E "/(flyway-core|flyway-database-postgresql)-[0-9][^/]*\.jar$|/org/postgresql/postgresql/[^/]*/postgresql-[^/]*\.jar$|/com/fasterxml/jackson/core/jackson-(core|databind|annotations)/|/jackson-dataformat-yaml/" \
    > "$WORK/jars.txt"
  grep -q flyway-core "$WORK/jars.txt" || die "flyway-core not on the resolved classpath"
  CP="$(paste -sd: "$WORK/jars.txt")"
  say "flyway: $(grep -o 'flyway-core-[0-9.]*' "$WORK/jars.txt" | head -1)"
}

compile_probe() {
  mkdir -p "$WORK/java"
  cat > "$WORK/java/StageFFlyway.java" <<'JAVA'
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import java.util.*;

public class StageFFlyway {
    static class TenantBind extends org.flywaydb.core.api.callback.BaseCallback {
        public boolean supports(org.flywaydb.core.api.callback.Event e,
                                org.flywaydb.core.api.callback.Context c) {
            return e == org.flywaydb.core.api.callback.Event.BEFORE_EACH_MIGRATE;
        }
        public void handle(org.flywaydb.core.api.callback.Event e,
                           org.flywaydb.core.api.callback.Context c) {
            try (java.sql.Statement st = c.getConnection().createStatement()) {
                st.execute("SELECT set_config('app.tenant_id',"
                    + "'00000000-0000-0000-0000-000000000001',true)");
            } catch (Exception x) { throw new IllegalStateException(x); }
        }
    }
    public static void main(String[] a) {
        Map<String,String> o = new HashMap<>();
        for (String s : a) { int i = s.indexOf('='); o.put(s.substring(2, i), s.substring(i + 1)); }
        FluentConfiguration c = Flyway.configure()
            .dataSource(o.get("url"), o.get("user"), o.get("password"))
            .locations(o.get("locations").split(","))
            .baselineOnMigrate(Boolean.parseBoolean(o.getOrDefault("baseline", "false")))
            .outOfOrder(true);
        // The tenant binding the image registers via quarkus.flyway.callbacks.
        // The two-role cases migrate as a SUPERUSER and never needed it —
        // superusers bypass RLS. The CE cases migrate as the app role, which
        // does not, and without this V7 cannot fill `team.alias`: FORCE RLS
        // hides the rows it has to update, and the NOT NULL then fails with
        // "column \"alias\" of relation \"team\" contains null values".
        if (!"false".equals(o.get("tenantCallback"))) c.callbacks(new TenantBind());
        if (o.containsKey("target")) c.target(org.flywaydb.core.api.MigrationVersion.fromVersion(o.get("target")));
        Flyway f = c.load();
        String action = o.getOrDefault("action", "migrate");
        try {
            switch (action) {
                case "migrate" -> System.out.println("RESULT migrate OK executed=" + f.migrate().migrationsExecuted);
                case "validate" -> { f.validate(); System.out.println("RESULT validate OK"); }
                case "info" -> {
                    var i = f.info();
                    System.out.println("RESULT info current=" + (i.current() == null ? "null" : i.current().getVersion())
                        + " pending=" + i.pending().length);
                }
                default -> throw new IllegalArgumentException(action);
            }
        } catch (Throwable t) {
            System.out.println("RESULT FAILED " + t.getClass().getSimpleName());
            for (Throwable x = t; x != null; x = x.getCause())
                System.out.println("  cause: " + x.getClass().getSimpleName() + ": "
                    + String.valueOf(x.getMessage()).replaceAll("\\s+", " "));
        }
    }
}
JAVA
  javac -cp "$CP" -d "$WORK/java" "$WORK/java/StageFFlyway.java" 2>/dev/null \
    || die "could not compile the flyway driver"
}

flyway() { java -cp "$WORK/java:$CP" StageFFlyway --url="jdbc:postgresql://localhost:$PORT/$DB" \
             --user="$MIGRATOR" --password= "$@" 2>&1 | grep -vE '^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}'; }

sqla() { docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d postgres -qtAc "$1"; }
# sqla() is pinned to the `postgres` database. Anything that asks about a
# NAMED database — which every CE case does, because db-mismatch deliberately
# uses two — must say which, or it reads the wrong catalogue and reports a
# green that measured nothing.
sqlin() { docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d "$1" -qtAc "$2"; }
sql()  { docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d "$DB" -qtAc "$1"; }
sqlq() { docker exec -i "$CT" psql -U "$MIGRATOR" -d "$DB" -qtAc "$1" 2>&1; }   # may fail, captured
as()   { docker exec -i "$CT" psql -U "$1" -d "$DB" -qtAc "$2" 2>&1; }

reset_cluster() {
  docker rm -f "$CT" >/dev/null 2>&1 || true
  docker run -d --name "$CT" -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_PASSWORD=probe \
    -p "$PORT":5432 "$PG_IMAGE" >/dev/null
  # Waiting for a connection is not enough on its own. The official image starts
  # the cluster TWICE — once for initdb with a unix socket only, then again for
  # real — and both pg_isready and a plain SELECT answer yes to the first one.
  # A probe that continued there met the second start mid-flight: "the database
  # system is starting up", and one reset later "No such file or directory" for
  # a socket that had just gone away. Measured while adding the revert cases,
  # which multiplied the resets per run from ten to fifteen.
  #
  # So: wait for the image to say the init process is done, and only then for a
  # statement to succeed. The first waits out the restart; the second waits for
  # the cluster that will still be there afterwards.
  for i in $(seq 1 90); do
    docker logs "$CT" 2>&1 | grep -q 'init process complete' && break
    sleep 1; [[ $i -lt 90 ]] || die "probe container never finished initdb"; done
  for i in $(seq 1 90); do
    docker exec "$CT" psql -U "$MIGRATOR" -d postgres -qtAc 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1; [[ $i -lt 90 ]] || die "probe container never became ready"; done
  sqla "CREATE DATABASE $DB"
  sql "CREATE ROLE $RUNTIME LOGIN NOSUPERUSER NOBYPASSRLS" >/dev/null
}

# ---------------------------------------------------------------------------
# The CE shape: ONE role, which migrates AND runs, exactly as init-db.sh
# creates it. This is the installation a self-hoster gets, and it is the shape
# the two-role cases above never model — which is why B1 and B2 were only
# found by review and not by this suite.
#
# Read from init-db.sh rather than retyped, so the probe cannot drift away
# from the artefact it is supposed to witness.
# ---------------------------------------------------------------------------
INIT_DB_SH="${INIT_DB_SH:-$SERVER_ROOT/ops/postgres/init-db.sh}"

reset_cluster_ce() {   # reset_cluster_ce [dbname]
  local db="${1:-$DB}"
  docker rm -f "$CT" >/dev/null 2>&1 || true
  docker run -d --name "$CT" -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_PASSWORD=probe \
    -p "$PORT":5432 "$PG_IMAGE" >/dev/null
  for i in $(seq 1 90); do
    docker logs "$CT" 2>&1 | grep -q 'init process complete' && break
    sleep 1; [[ $i -lt 90 ]] || die "probe container never finished initdb"; done
  for i in $(seq 1 90); do
    docker exec "$CT" psql -U "$MIGRATOR" -d postgres -qtAc 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1; [[ $i -lt 90 ]] || die "probe container never became ready"; done

  # init-db.sh's own statements, with its environment supplied. Everything
  # about the role — its attributes, its pinned search_path, the four chain
  # roles it pre-creates — therefore comes from the file under test.
  docker exec -i \
    -e POSTGRES_USER="$MIGRATOR" \
    -e KEYCLOAK_DB_USER=kc -e KEYCLOAK_DB_PASSWORD=kc -e KEYCLOAK_DB_NAME=kc \
    -e KUMBUKA_DB_USER="$CE_ROLE" -e KUMBUKA_DB_PASSWORD=probe -e KUMBUKA_DB_NAME="$db" \
    "$CT" bash -s < "$INIT_DB_SH" >/dev/null \
    || die "init-db.sh failed against the probe cluster"
}

# The app role's attributes, as one string, so "did init-db.sh keep it
# unprivileged" is a single comparison. BYPASSRLS on the runtime role would
# switch off the tenant isolation, so this is a security assertion, not tidiness.
ce_role_attrs() {
  sqla "SELECT rolsuper||'/'||rolcreaterole||'/'||rolbypassrls
          FROM pg_roles WHERE rolname='$CE_ROLE'"
}

# flyway as an arbitrary role against an arbitrary database.
flyway_as() { local user="$1" db="$2"; shift 2
  java -cp "$WORK/java:$CP" StageFFlyway --url="jdbc:postgresql://localhost:$PORT/$db" \
    --user="$user" --password= "$@" 2>&1 | grep -vE '^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}'; }

# The upgrade/revert step run by an arbitrary role, against an arbitrary db.
step_as() {  # step_as <sqlfile> <role> <db> [dbparam]
  docker exec -i "$CT" psql -U "$2" -d "$3" -v ON_ERROR_STOP=1 \
    -v migrator="$2" -v runtime="$2" -v db="${4:-$3}" -q 2>&1 < "$1"; }

# The chain as the OLD image carries it: V1..V22 only.
old_chain_dir() {
  local d="$WORK/oldchain"; mkdir -p "$d"
  cp "$MIGRATIONS"/*.sql "$d"/
  rm -f "$d"/V23__*.sql
  printf '%s' "$d"
}

# EE chain, materialised from the platform repo when present, otherwise skipped.
ee_chain_dir() {
  local d="$WORK/ee" p="${PLATFORM_ROOT:-$SERVER_ROOT/../platform}"
  mkdir -p "$d"
  find "$p" -path '*/db/ee-migration/*.sql' -exec cp {} "$d"/ \; 2>/dev/null || true
  printf '%s' "$d"
}

run_upgrade_step() { docker exec -i "$CT" psql -U "$MIGRATOR" -d "$DB" -v ON_ERROR_STOP=1 \
                       -v migrator="$MIGRATOR" -v runtime="$RUNTIME" -v db="$DB" -q 2>&1 < "$UPGRADE_SQL"; }

run_revert_step()  { docker exec -i "$CT" psql -U "$MIGRATOR" -d "$DB" -v ON_ERROR_STOP=1 \
                       -v migrator="$MIGRATOR" -v runtime="$RUNTIME" -v db="$DB" -q 2>&1 < "$REVERT_SQL"; }

# The three facts the relocation writes, read back as one string so that "was
# anything touched" is a single comparison. Used by every RED revert case: a
# refusal that changed one of the three is not a refusal.
stage_f_state() {
  sql "SELECT coalesce(to_regclass('platform.flyway_schema_history')::text,'-')
           || '|' || coalesce(to_regclass('public.flyway_schema_history')::text,'-')
           || '|' || (SELECT count(*) FROM pg_db_role_setting)
           || '|' || has_schema_privilege('$RUNTIME','platform','USAGE')"
}

# What Hibernate's schema validation actually asks, asked the same way.
#
# It resolves an unqualified entity against the connection's ONE default schema
# — current_schema(), the first entry of the search_path — and looks for the
# table there. It does not walk the rest of the path. So the question is not
# "can this role reach user_account" but "is user_account in the schema this
# connection defaults to", and that is what this asks, as the runtime role.
#
# A stand-in for Hibernate and named as one: the probe carries no entity
# mappings and building a persistence unit here would be a second model of the
# application's own. What it reproduces exactly is the resolution rule that
# sprint/185.2 measured the old image failing on.
hibernate_would_find() {   # hibernate_would_find <table>
  as "$RUNTIME" "SELECT coalesce(to_regclass(current_schema() || '.$1')::text, '<absent>')"
}

owner_sweep() {   # what the deployment's bootstrap does; the view only binds under a non-super owner
  sql "DO \$\$ DECLARE o record; BEGIN
         FOR o IN SELECT n.nspname s, c.relname r, c.relkind k FROM pg_class c
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname=ANY(ARRAY['public','platform'])
                    AND c.relkind=ANY(ARRAY['r','p','v','m']::\"char\"[])
                    AND pg_get_userbyid(c.relowner) <> '$RUNTIME' LOOP
           EXECUTE format(CASE o.k WHEN 'v' THEN 'ALTER VIEW %I.%I OWNER TO $RUNTIME'
                                   ELSE 'ALTER TABLE %I.%I OWNER TO $RUNTIME' END, o.s, o.r);
         END LOOP; END \$\$;" >/dev/null
}

relations_in() { sql "SELECT coalesce(string_agg(c.relname, ',' ORDER BY c.relname),'')
                        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                       WHERE n.nspname='$1' AND c.relkind=ANY(ARRAY['r','p']::\"char\"[])"; }

history_in() { sql "SELECT coalesce(to_regclass('$1.flyway_schema_history')::text,'<absent>')"; }

# --- a role's settings, read the way the revert has to read them -------------
#
# `setconfig` is the role's WHOLE list of settings and entries are appended in
# the order they were set, so nothing puts `search_path` first. These three ask
# by name and report the position rather than assuming one — which is the fact
# the revert-extra case and the second half of red-revert-path turn on.
#
# <scope> is `db` for the migrator, whose setting is IN DATABASE, and `global`
# for the runtime role, whose setting is not.
setting_scope() {   # setting_scope <db|global>
  case "$1" in
    db)     printf "s.setdatabase = (SELECT oid FROM pg_database WHERE datname = '%s')" "$DB" ;;
    global) printf 's.setdatabase = 0' ;;
    *)      die "setting_scope: unknown scope: $1" ;;
  esac
}

role_settings() {   # role_settings <role> <db|global> — every entry, in catalogue order
  sql "SELECT coalesce((SELECT array_to_string(s.setconfig, ' ; ')
                          FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole
                         WHERE r.rolname = '$1' AND $(setting_scope "$2")), '<none>')"
}

search_path_index() {   # search_path_index <role> <db|global> — 0 when there is none
  sql "SELECT coalesce((SELECT min(i) FROM pg_db_role_setting s
                          JOIN pg_roles r ON r.oid = s.setrole
                          CROSS JOIN LATERAL generate_subscripts(s.setconfig, 1) AS i
                         WHERE r.rolname = '$1' AND $(setting_scope "$2")
                           AND s.setconfig[i] LIKE 'search_path=%'), 0)"
}

search_path_of() {   # search_path_of <role> <db|global> — the entry itself, wherever it stands
  sql "SELECT coalesce((SELECT cfg FROM pg_db_role_setting s
                          JOIN pg_roles r ON r.oid = s.setrole
                          CROSS JOIN LATERAL unnest(s.setconfig) AS cfg
                         WHERE r.rolname = '$1' AND $(setting_scope "$2")
                           AND cfg LIKE 'search_path=%'), '<none>')"
}

seed_two_tenants() {
  sql "INSERT INTO platform.user_account (tenant_id, subject, email, role, status) VALUES
         ('$TENANT_A','subject-a','a@example.test','member','active'),
         ('$TENANT_B','subject-b','b@example.test','member','active');
       INSERT INTO platform.scope (tenant_id, name, slug, kind) VALUES
         ('$TENANT_A','A One','project-a','project'),
         ('$TENANT_B','B One','project-b','project');" >/dev/null
}

read_view_as() {   # read_view_as <role> <tenant> <subject>
  docker exec -i "$CT" psql -U "$1" -d "$DB" -qtA <<SQL 2>&1
SET app.tenant_id = '$2';
SET app.subject   = '$3';
SELECT slug FROM platform.scope_access ORDER BY slug;
SQL
}

# ===========================================================================
case_fresh() {
  hdr "FRESH — the chain on an empty database, then the upgrade step"
  reset_cluster
  flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=migrate | tail -2

  local pub; pub="$(relations_in public)"
  if [[ "$pub" == "content_relation,flyway_schema_history,memory" ]]; then
    ok "public holds exactly memory, content_relation and the history"
  else
    bad "public holds exactly memory, content_relation and the history" "observed: $pub"
  fi

  local plat; plat="$(relations_in platform)"
  case "$plat" in
    *governance_audit*scope*team*user_account*) ok "the inventory is in platform ($plat)" ;;
    *) bad "the inventory is in platform" "observed: $plat" ;;
  esac
  case "$plat" in
    *import_credits*tenant_limits*) ok "the EE tables came along (V10002)" ;;
    *) say "EE chain not present in this checkout — V10002 not exercised" ;;
  esac

  say "history before the step: public=$(history_in public) platform=$(history_in platform)"
  run_upgrade_step | sed 's/^/       /'
  if [[ "$(history_in platform)" != "<absent>" && "$(history_in public)" == "<absent>" ]]; then
    ok "the upgrade step moved the history into platform"
  else
    bad "the upgrade step moved the history into platform" \
        "public=$(history_in public) platform=$(history_in platform)"
  fi

  local out; out="$(flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=info)"
  printf '%s\n' "$out" | grep -q 'pending=0' \
    && ok "a restart finds the history and reports nothing pending" \
    || bad "a restart finds the history and reports nothing pending" "$out"
  out="$(flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=validate)"
  printf '%s\n' "$out" | grep -q 'validate OK' \
    && ok "validate is green after the move" || bad "validate is green after the move" "$out"

  say "idempotency — the same step again:"
  out="$(run_upgrade_step)"
  printf '%s\n' "$out" | grep -qi 'already in platform' \
    && ok "a second run says it has nothing to do" || bad "a second run says it has nothing to do" "$out"
}

# ===========================================================================
case_prod() {
  hdr "PROD — production state, upgraded in the rollout's order (step, then image)"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public
  local before; before="$(read_view_as "$RUNTIME" "$TENANT_A" subject-a)"
  say "view before the move (tenant A): $(printf '%s' "$before" | tr '\n' ' ')"

  say "step 1 of the rollout — the upgrade step, old image still serving:"
  run_upgrade_step | sed 's/^/       /'
  [[ "$(history_in platform)" != "<absent>" ]] \
    && ok "history is in platform while the old image is still up" \
    || bad "history is in platform while the old image is still up" "$(history_in public)"

  say "step 2 of the rollout — the new chain:"
  flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=migrate | tail -2
  owner_sweep
  local pub; pub="$(relations_in public)"
  [[ "$pub" == "content_relation,memory" ]] \
    && ok "public is down to the two memory tables" \
    || bad "public is down to the two memory tables" "observed: $pub"

  local after; after="$(read_view_as "$RUNTIME" "$TENANT_A" subject-a)"
  [[ "$after" == "$before" ]] \
    && ok "scope_access returns the same rows as before the move" \
    || bad "scope_access returns the same rows as before the move" "before=[$before] after=[$after]"

  local out; out="$(flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=validate)"
  printf '%s\n' "$out" | grep -q 'validate OK' && ok "validate is green" || bad "validate is green" "$out"
}

seed_two_tenants_public() {
  sql "INSERT INTO user_account (tenant_id, subject, email, role, status) VALUES
         ('$TENANT_A','subject-a','a@example.test','member','active'),
         ('$TENANT_B','subject-b','b@example.test','member','active');
       INSERT INTO scope (tenant_id, name, slug, kind) VALUES
         ('$TENANT_A','A One','project-a','project'),
         ('$TENANT_B','B One','project-b','project');" >/dev/null
  sql "GRANT USAGE ON SCHEMA platform TO $RUNTIME" >/dev/null
}

# ===========================================================================
case_rollback() {
  hdr "ROLLBACK — the previous chain meeting an upgraded database"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  run_upgrade_step >/dev/null
  flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=migrate | tail -1

  say "now the OLD chain (V1..V22, no V23) against that database:"
  local out; out="$(flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate)"
  printf '%s\n' "$out" | sed 's/^/       /'
  # What the dispatch expected here is that V23 counts as a future migration and
  # goes unremarked. It does not. Flyway 12 validates as part of migrate, and an
  # applied version it cannot resolve locally is a hard stop.
  if printf '%s\n' "$out" | grep -q 'migrate OK'; then
    ok "the old chain migrates against the upgraded database"
  elif printf '%s\n' "$out" | grep -q 'not resolved locally: 23'; then
    gap "the previous image cannot migrate against a V23 database" \
        "Flyway refuses with 'Detected applied migration not resolved locally: 23'. This is not specific to V23 — ANY added migration does it — so the rollback path was never open once a release adds one. Rolling back needs flyway repair, or the previous image plus the previous chain."
  else
    bad "the old chain's behaviour is one of the two known ones" "$out"
  fi

  # The half that DOES hold: it finds the history where the step put it.
  out="$(flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=info)"
  say "old chain info: $out"
  printf '%s\n' "$out" | grep -q 'pending=0' \
    && ok "the old chain still FINDS the relocated history (nothing pending)" \
    || bad "the old chain still finds the relocated history" "$out"
}

# ===========================================================================
# The window the rollout opens between its step 2 and its step 3: the upgrade
# step has run, the new image has NOT been deployed yet, and the old one is
# still the thing that would come back up on a restart.
# ===========================================================================
case_window() {
  hdr "WINDOW — the old image between the upgrade step and the new release"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  run_upgrade_step >/dev/null

  local out; out="$(flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate)"
  printf '%s\n' "$out" | grep -q 'migrate OK' \
    && ok "the old chain migrates cleanly in the window (no new migration exists yet)" \
    || bad "the old chain migrates cleanly in the window" "$out"

  # The half Flyway does not cover. Hibernate resolves an unqualified entity
  # against the connection's ONE default schema, not along the search_path, so
  # what matters is where current_schema() points and whether the tables are
  # there.
  local cs pub plat
  cs="$(sql "SELECT current_schema()")"
  pub="$(relations_in public)"; plat="$(relations_in platform)"
  say "after the step: current_schema()=$cs"
  say "  public  : $pub"
  say "  platform: $plat"
  if [[ "$cs" == "platform" && "$plat" != *scope* ]]; then
    gap "in this window an unqualified entity mapping resolves into an empty schema" \
        "current_schema() is already 'platform' while the tables are still in 'public'. Flyway is fine, but a restart of the OLD image validates its entities against 'platform' and finds nothing. The window still exists and must be kept short — but since sprint/185.3 it is no longer one-way: stage-f-relocate-history-revert.sql closes it, and red-revert-window measures that it does."
  else
    ok "current_schema() and the tables agree in the window (cs=$cs)"
  fi
}

# ===========================================================================
case_routing() {
  hdr "ROUTING — team_tenant_id_by_alias across the move"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1
  owner_sweep
  sql "INSERT INTO team (tenant_id, name, alias) VALUES ('$TENANT_A','Alpha','alpha')" >/dev/null

  install_routing_fn "platform, public, pg_temp"
  local before; before="$(sqlq "SELECT team_tenant_id_by_alias('alpha')" || true)"
  [[ "$before" == "$TENANT_A" ]] \
    && ok "the new pin resolves BEFORE the move (tables still in public)" \
    || bad "the new pin resolves BEFORE the move" "$before"

  run_upgrade_step >/dev/null
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  owner_sweep
  local after; after="$(sqlq "SELECT team_tenant_id_by_alias('alpha')" || true)"
  [[ "$after" == "$TENANT_A" ]] \
    && ok "the new pin still resolves AFTER the move (tables in platform)" \
    || bad "the new pin still resolves AFTER the move" "$after"
}

install_routing_fn() {  # install_routing_fn "<search_path>"
  sql "CREATE OR REPLACE FUNCTION team_tenant_id_by_alias(p_alias text)
         RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER
         SET search_path = $1
         AS \$\$ SELECT tenant_id FROM team WHERE alias = p_alias \$\$;" >/dev/null
}

# ===========================================================================
# CATALOGUE — who may touch what in platform, after the move.
#
# The provider role is created here the way the ops-console bootstrap leaves it:
# renamed from the chain's kumbuka_ops_reader to kumbuka_operator, holding the
# table grants V6 gave it and nothing on the schema platform beyond USAGE.
# ===========================================================================
prepare_moved_db_with_operator() {
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  sql "ALTER ROLE kumbuka_ops_reader RENAME TO kumbuka_operator" >/dev/null
  run_upgrade_step >/dev/null
  flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  # what ops-console's bootstrap 14 does
  sql "GRANT USAGE ON SCHEMA platform TO kumbuka_operator" >/dev/null
  sql "ALTER ROLE kumbuka_operator SET search_path = platform, public" >/dev/null
}

assert_operator_walls() {
  local t r
  for t in public.memory public.content_relation platform.scope_access; do
    r="$(as kumbuka_operator "SELECT 1 FROM $t LIMIT 1" || true)"
    if printf '%s' "$r" | grep -q 'permission denied'; then
      ok "kumbuka_operator is refused on $t"
    else
      bad "kumbuka_operator is refused on $t" "got: $(printf '%s' "$r" | tr '\n' ' ')"
    fi
  done
}

assert_platform_grantees() {
  # Two different questions, and lumping them together hides both.
  #
  # (a) The VIEW is the steering services' contract. Its readers are enumerated
  #     by V21 and V22, one grant at a time, and the provider role is
  #     deliberately not among them — that absence is the ops-no-content
  #     boundary.
  #
  # (b) The moved TABLES carry the ACLs they already had in public; Postgres
  #     holds them on the relation, so they travel with it. The provider role
  #     keeping its V6 grants is therefore the CORRECT outcome of the move, not
  #     a widening — it held them yesterday in `public` and holds the same ones
  #     today in `platform`.
  local view_grantees table_grantees
  view_grantees="$(sql "SELECT coalesce(string_agg(DISTINCT g, ',' ORDER BY g),'') FROM (
                          SELECT pg_get_userbyid((aclexplode(c.relacl)).grantee) AS g
                            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                           WHERE n.nspname='platform' AND c.relname='scope_access'
                             AND c.relacl IS NOT NULL) x
                         WHERE g <> '$RUNTIME'")"
  [[ "$view_grantees" == "kumbuka_logbook,kumbuka_memory,kumbuka_worklist" ]] \
    && ok "platform.scope_access is readable by exactly the three enumerated services" \
    || bad "platform.scope_access is readable by exactly the three enumerated services" \
          "got [$view_grantees]"

  table_grantees="$(sql "SELECT coalesce(string_agg(DISTINCT g, ',' ORDER BY g),'') FROM (
                           SELECT pg_get_userbyid((aclexplode(c.relacl)).grantee) AS g
                             FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                            WHERE n.nspname='platform'
                              AND c.relkind=ANY(ARRAY['r','p']::\"char\"[])
                              AND c.relacl IS NOT NULL) x
                          WHERE g <> '$RUNTIME'")"
  [[ "$table_grantees" == "kumbuka_operator" ]] \
    && ok "the moved tables carry exactly their old grantee (kumbuka_operator), nothing new" \
    || bad "the moved tables carry exactly their old grantee" "got [$table_grantees]"
}

case_catalogue() {
  hdr "CATALOGUE — the provider boundary after the move"
  prepare_moved_db_with_operator
  assert_operator_walls
  assert_platform_grantees
  say "schema ACL: $(sql "SELECT nspacl::text FROM pg_namespace WHERE nspname='platform'")"
}

# ===========================================================================
# RED PROBES
# ===========================================================================
case_red_halfstate() {
  hdr "RED — a half-finished relocation must stop the step, without changing anything"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1

  say "green control first: the same database, no half state"
  local out; out="$(run_upgrade_step)"
  printf '%s\n' "$out" | grep -qiE 'history moved to platform' \
    && ok "CONTROL: without the half state the step runs green" \
    || bad "CONTROL: without the half state the step runs green" "$out"

  say "now manufacture the half state: a history in BOTH schemas"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1
  sql "CREATE TABLE platform.flyway_schema_history AS SELECT * FROM public.flyway_schema_history" >/dev/null
  local before_pub before_plat settings_before
  before_pub="$(history_in public)"; before_plat="$(history_in platform)"
  settings_before="$(sql "SELECT count(*) FROM pg_db_role_setting")"

  out="$(run_upgrade_step || true)"
  printf '%s\n' "$out" | sed 's/^/       /'
  printf '%s\n' "$out" | grep -qi 'BOTH public and platform' \
    && ok "RED: the step refuses on the half state, naming it" \
    || bad "RED: the step refuses on the half state" "$out"
  [[ "$(history_in public)" == "$before_pub" && "$(history_in platform)" == "$before_plat" \
     && "$(sql "SELECT count(*) FROM pg_db_role_setting")" == "$settings_before" ]] \
    && ok "RED: nothing was changed — both histories and the role settings are as they were" \
    || bad "RED: nothing was changed" "history or pg_db_role_setting moved"
}

case_red_baseline() {
  hdr "RED — baseline-on-migrate is what hides a half state"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1
  sql "ALTER ROLE $MIGRATOR SET search_path = platform, public" >/dev/null
  say "history still in public, migrator now looking at platform (the half state)"

  local off on
  off="$(flyway --locations="filesystem:$(old_chain_dir)" --action=migrate --baseline=false)"
  printf '%s\n' "$off" | sed 's/^/       /'
  printf '%s\n' "$off" | grep -q 'RESULT FAILED' \
    && ok "with baseline-on-migrate OFF Flyway refuses loudly" \
    || bad "with baseline-on-migrate OFF Flyway refuses loudly" "$off"
  [[ "$(history_in platform)" == "<absent>" ]] \
    && ok "and it wrote no history into platform" \
    || bad "and it wrote no history into platform" "$(history_in platform)"

  say "CONTROL: the same database with the setting back ON"
  on="$(flyway --locations="filesystem:$(old_chain_dir)" --action=migrate --baseline=true)"
  printf '%s\n' "$on" | sed 's/^/       /'
  [[ "$(history_in platform)" != "<absent>" ]] \
    && ok "CONTROL: with it ON Flyway silently invents a second history — the behaviour we turned off" \
    || bad "CONTROL: with it ON Flyway invents a baseline" "$on"
}

case_red_catalogue() {
  hdr "RED — a granted SELECT on the view must turn the catalogue probe red"
  prepare_moved_db_with_operator
  say "control: the walls stand"
  assert_operator_walls
  say "now break it, exactly as a careless grant would"
  sql "GRANT SELECT ON platform.scope_access TO kumbuka_operator" >/dev/null
  local r; r="$(as kumbuka_operator "SELECT 1 FROM platform.scope_access LIMIT 1" || true)"
  if printf '%s' "$r" | grep -q 'permission denied'; then
    bad "RED: the probe must go red on a granted view" "still refused — the probe cannot see the breach"
  else
    ok "RED: with the grant the read succeeds — the probe is capable of going red"
  fi
  local got; got="$(sql "SELECT count(*) FROM (SELECT pg_get_userbyid((aclexplode(c.relacl)).grantee) g
                           FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                          WHERE n.nspname='platform' AND c.relacl IS NOT NULL) x
                          WHERE g='kumbuka_operator'")"
  [[ "$got" != "0" ]] \
    && ok "RED: and the grantee list names kumbuka_operator" \
    || bad "RED: the grantee list names kumbuka_operator" "count=$got"
}

case_red_routing() {
  hdr "RED — the OLD pin stops resolving once the tables move"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1
  owner_sweep
  sql "INSERT INTO team (tenant_id, name, alias) VALUES ('$TENANT_A','Alpha','alpha')" >/dev/null
  install_routing_fn "public, pg_temp"
  local before; before="$(sqlq "SELECT team_tenant_id_by_alias('alpha')" || true)"
  [[ "$before" == "$TENANT_A" ]] \
    && ok "CONTROL: the old pin resolves while team is still in public" \
    || bad "CONTROL: the old pin resolves before the move" "$before"

  run_upgrade_step >/dev/null
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  owner_sweep
  local after; after="$(sqlq "SELECT team_tenant_id_by_alias('alpha')" || true)"
  if printf '%s' "$after" | grep -qi 'does not exist'; then
    ok "RED: after the move the old pin fails — this is what the new pin repairs"
  else
    bad "RED: after the move the old pin must fail" "got: $(printf '%s' "$after" | tr '\n' ' ')"
  fi
  install_routing_fn "platform, public, pg_temp"
  after="$(sqlq "SELECT team_tenant_id_by_alias('alpha')" || true)"
  [[ "$after" == "$TENANT_A" ]] \
    && ok "and with the new pin the same database resolves again" \
    || bad "with the new pin the same database resolves again" "$after"
}


# ===========================================================================
# The way back. This is the case the whole file exists around: between the
# upgrade step and the deploy of the V23 image the old image cannot restart,
# and until sprint/185.3 there was nothing to undo the step with.
# ===========================================================================
case_revert() {
  hdr "REVERT — production state, the step, and then the way back"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public

  local before_state; before_state="$(stage_f_state)"
  say "before the step: $before_state"

  run_upgrade_step >/dev/null
  say "after the step:  $(stage_f_state)"

  say "the way back:"
  run_revert_step | sed 's/^/       /'

  # The three facts the dispatch names, each on its own so a failure says which.
  [[ "$(history_in public)" != "<absent>" && "$(history_in platform)" == "<absent>" ]] \
    && ok "the history is back in public" \
    || bad "the history is back in public" \
           "public=$(history_in public) platform=$(history_in platform)"

  local settings; settings="$(sql "SELECT count(*) FROM pg_db_role_setting")"
  [[ "$settings" == "0" ]] \
    && ok "pg_db_role_setting carries no row for either role" \
    || bad "pg_db_role_setting carries no row for either role" "rows: $settings"

  local usage; usage="$(sql "SELECT has_schema_privilege('$RUNTIME','platform','USAGE')")"
  [[ "$usage" == "f" ]] \
    && ok "$RUNTIME has no USAGE on platform" \
    || bad "$RUNTIME has no USAGE on platform" "has_schema_privilege said $usage"

  # And the point of all three: the old image starts.
  local out; out="$(flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=validate)"
  printf '%s\n' "$out" | grep -q 'validate OK' \
    && ok "the old chain validates green against the reverted database" \
    || bad "the old chain validates green against the reverted database" "$out"

  local found; found="$(hibernate_would_find user_account)"
  [[ "$found" == *user_account* ]] \
    && ok "an unqualified entity resolves again ($found)" \
    || bad "an unqualified entity resolves again" "current_schema() holds: $found"

  say "idempotency — the same revert again:"
  out="$(run_revert_step)"
  printf '%s\n' "$out" | grep -qi 'already in the pre-stage-F state' \
    && ok "a second revert says it has nothing to do" \
    || bad "a second revert says it has nothing to do" "$out"
}

# ===========================================================================
# The state the revert used to walk past: a role whose setconfig carries more
# than the search_path, with the search_path NOT first. Entries are appended in
# the order they were set, so any setting a deployment made before stage F ran
# stands ahead of it. A revert reading setconfig[1] then finds no search_path at
# all, leaves its variables NULL, skips the RESET that hangs on them — and still
# reports the pre-stage-F state restored, while the old image goes on failing to
# start. Measured in sprint/185.5, corrected there.
# ===========================================================================
case_revert_extra() {
  hdr "REVERT — a setting standing BEFORE the search_path: the reset still happens, and the setting survives"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public

  say "both roles carry a statement_timeout BEFORE the step runs:"
  sql "ALTER ROLE $MIGRATOR IN DATABASE $DB SET statement_timeout = '31s'" >/dev/null
  sql "ALTER ROLE $RUNTIME SET statement_timeout = '31s'" >/dev/null
  say "migrator: $(role_settings "$MIGRATOR" db)"
  say "runtime:  $(role_settings "$RUNTIME" global)"

  run_upgrade_step >/dev/null
  say "after the step — the search_path is appended, behind what was there:"
  say "migrator: $(role_settings "$MIGRATOR" db)"
  say "runtime:  $(role_settings "$RUNTIME" global)"

  # Asserted, not merely printed. If the search_path ever came first here the
  # case would pass against a revert that reads index 1 and would witness
  # nothing at all — so the position is the first thing it checks.
  local mi ri; mi="$(search_path_index "$MIGRATOR" db)"; ri="$(search_path_index "$RUNTIME" global)"
  [[ "$mi" -gt 1 && "$ri" -gt 1 ]] \
    && ok "the search_path does not stand at index 1 (migrator=$mi runtime=$ri)" \
    || bad "the search_path must not stand at index 1 for this case to witness anything" \
           "migrator=$mi runtime=$ri"

  say "the way back:"
  run_revert_step | sed 's/^/       /'

  [[ "$(search_path_index "$MIGRATOR" db)" == "0" && "$(search_path_index "$RUNTIME" global)" == "0" ]] \
    && ok "the search_path of both roles is reset" \
    || bad "the search_path of both roles is reset" \
           "migrator=[$(role_settings "$MIGRATOR" db)] runtime=[$(role_settings "$RUNTIME" global)]"

  [[ "$(role_settings "$MIGRATOR" db)" == *statement_timeout=31s* \
     && "$(role_settings "$RUNTIME" global)" == *statement_timeout=31s* ]] \
    && ok "and the other setting survives — RESET search_path removes the entry, not the row" \
    || bad "the other setting survives the revert" \
           "migrator=[$(role_settings "$MIGRATOR" db)] runtime=[$(role_settings "$RUNTIME" global)]"

  [[ "$(history_in public)" != "<absent>" && "$(history_in platform)" == "<absent>" ]] \
    && ok "the history is back in public" \
    || bad "the history is back in public" \
           "public=$(history_in public) platform=$(history_in platform)"

  local found; found="$(hibernate_would_find user_account)"
  [[ "$found" == *user_account* ]] \
    && ok "an unqualified entity resolves again ($found)" \
    || bad "an unqualified entity resolves again" "current_schema() holds: $found"

  # The migrator's side of the same fact, and the sharper half of it. The
  # runtime role loses USAGE on `platform` in the same revert, so its
  # current_schema() falls through to `public` whether the search_path was
  # reset or not — a stale setting stays invisible there. The migrator is the
  # superuser and never loses USAGE, so a search_path left pointing at
  # `platform` sends Flyway looking for its history in a schema that no longer
  # holds one. That is what the old image actually trips over.
  local out; out="$(flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=validate)"
  printf '%s\n' "$out" | grep -q 'validate OK' \
    && ok "the old chain validates green — the migrator finds its history where it now is" \
    || bad "the old chain validates green against the reverted database" "$out"
}

# ===========================================================================
case_reforward() {
  hdr "REFORWARD — step, revert, step: the second move is green"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public

  run_upgrade_step >/dev/null
  run_revert_step  >/dev/null

  local out; out="$(run_upgrade_step)"
  printf '%s\n' "$out" | grep -qi 'history moved to platform' \
    && ok "the second relocation runs green" \
    || bad "the second relocation runs green" "$out"

  [[ "$(history_in platform)" != "<absent>" && "$(history_in public)" == "<absent>" ]] \
    && ok "and the history is in platform again" \
    || bad "and the history is in platform again" \
           "public=$(history_in public) platform=$(history_in platform)"

  out="$(flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=migrate)"
  printf '%s\n' "$out" | grep -q 'migrate OK' \
    && ok "the new chain migrates after the round trip" \
    || bad "the new chain migrates after the round trip" "$out"
}

# ===========================================================================
case_red_revert_v23() {
  hdr "RED — after V23 the revert must refuse and change nothing"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public
  run_upgrade_step >/dev/null

  say "green control first — the same database, V23 not yet applied:"
  local out; out="$(run_revert_step)"
  printf '%s\n' "$out" | grep -qi 'history is back in public' \
    && ok "CONTROL: before V23 the revert runs green" \
    || bad "CONTROL: before V23 the revert runs green" "$out"

  say "now forward again, and apply V23:"
  run_upgrade_step >/dev/null
  flyway --locations="filesystem:$MIGRATIONS,filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep

  local before; before="$(stage_f_state)"
  out="$(run_revert_step || true)"
  printf '%s\n' "$out" | sed 's/^/       /'
  printf '%s\n' "$out" | grep -qi 'forward only' \
    && ok "RED: the revert refuses after V23, and says the way is forward only" \
    || bad "RED: the revert refuses after V23" "$out"

  [[ "$(stage_f_state)" == "$before" ]] \
    && ok "RED: nothing was changed — history, role settings and the schema ACL are as they were" \
    || bad "RED: nothing was changed" "before=[$before] after=[$(stage_f_state)]"
}

# ===========================================================================
case_red_revert_path() {
  hdr "RED — a search_path somebody else set must stop the revert, unchanged"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public
  run_upgrade_step >/dev/null

  say "set the runtime role's search_path to something the relocation never writes:"
  sql "ALTER ROLE $RUNTIME SET search_path = platform, public, extensions" >/dev/null

  local before; before="$(stage_f_state)"
  local out; out="$(run_revert_step || true)"
  printf '%s\n' "$out" | sed 's/^/       /'
  printf '%s\n' "$out" | grep -qi 'not the .* the relocation sets' \
    && ok "RED: the revert refuses, naming both the found and the expected value" \
    || bad "RED: the revert refuses on a foreign search_path" "$out"

  [[ "$(stage_f_state)" == "$before" ]] \
    && ok "RED: nothing was changed — the foreign setting is still there" \
    || bad "RED: nothing was changed" "before=[$before] after=[$(stage_f_state)]"

  local still; still="$(search_path_of "$RUNTIME" global)"
  [[ "$still" == *extensions* ]] \
    && ok "RED: and specifically, the foreign value was NOT reset away" \
    || bad "RED: the foreign value survived the refusal" "observed: $still"

  say "green control — put the expected value back and run it again:"
  sql "ALTER ROLE $RUNTIME SET search_path = platform, public" >/dev/null
  out="$(run_revert_step)"
  printf '%s\n' "$out" | grep -qi 'history is back in public' \
    && ok "CONTROL: with the expected value the revert runs green on the same database" \
    || bad "CONTROL: with the expected value the revert runs green" "$out"

  # And again with the foreign value AWAY from index 1 — the refusal must not
  # depend on where in setconfig the entry happens to sit. A revert reading
  # index 1 finds nothing here, passes the check on a NULL, and drives on.
  say "once more, with a statement_timeout ahead of the search_path:"
  sql "ALTER ROLE $RUNTIME SET statement_timeout = '31s'" >/dev/null
  run_upgrade_step >/dev/null
  sql "ALTER ROLE $RUNTIME SET search_path = platform, public, extensions" >/dev/null
  local idx; idx="$(search_path_index "$RUNTIME" global)"
  say "runtime: $(role_settings "$RUNTIME" global)"
  [[ "$idx" -gt 1 ]] \
    && ok "the foreign search_path stands at index $idx, not 1" \
    || bad "the foreign search_path must not stand at index 1 for this half to witness anything" \
           "index=$idx"

  before="$(stage_f_state)"
  out="$(run_revert_step || true)"
  printf '%s\n' "$out" | sed 's/^/       /'
  printf '%s\n' "$out" | grep -qi 'not the .* the relocation sets' \
    && ok "RED: the revert refuses on a foreign value at index $idx too" \
    || bad "RED: the revert refuses on a foreign value away from index 1" "$out"

  [[ "$(stage_f_state)" == "$before" ]] \
    && ok "RED: nothing was changed by that refusal either" \
    || bad "RED: nothing was changed" "before=[$before] after=[$(stage_f_state)]"

  still="$(search_path_of "$RUNTIME" global)"
  [[ "$still" == *extensions* ]] \
    && ok "RED: the foreign value at index $idx was NOT reset away" \
    || bad "RED: the foreign value at index $idx survived the refusal" "observed: $still"

  say "green control — the expected value at that same place:"
  sql "ALTER ROLE $RUNTIME SET search_path = platform, public" >/dev/null
  [[ "$(search_path_index "$RUNTIME" global)" -gt 1 ]] \
    && ok "CONTROL: the expected value stands where the foreign one did (index $(search_path_index "$RUNTIME" global))" \
    || bad "CONTROL: the expected value stands where the foreign one did" \
           "index=$(search_path_index "$RUNTIME" global)"
  out="$(run_revert_step)"
  printf '%s\n' "$out" | grep -qi 'history is back in public' \
    && ok "CONTROL: and there the revert runs green" \
    || bad "CONTROL: with the expected value away from index 1 the revert runs green" "$out"
}

# ===========================================================================
# The counter-probe to the whole dispatch: the defect the revert removes.
# ===========================================================================
case_red_revert_window() {
  hdr "RED — without the revert the old image cannot start; with it, it can"
  reset_cluster
  flyway --locations="filesystem:$(old_chain_dir),filesystem:$(ee_chain_dir)" --action=migrate | tail -1
  owner_sweep
  seed_two_tenants_public

  say "the state the old image needs, before anything moved:"
  local found; found="$(hibernate_would_find user_account)"
  [[ "$found" == *user_account* ]] \
    && ok "CONTROL: before the step an unqualified entity resolves ($found)" \
    || bad "CONTROL: before the step an unqualified entity resolves" "$found"

  say "now the step, and the window it opens:"
  run_upgrade_step >/dev/null
  found="$(hibernate_would_find user_account)"
  [[ "$found" == "<absent>" ]] \
    && ok "RED: in the window the old image would find nothing where it looks ($found)" \
    || bad "RED: in the window the old image finds nothing" \
           "expected <absent> in current_schema(), got: $found"

  say "and the revert closes it:"
  run_revert_step >/dev/null
  found="$(hibernate_would_find user_account)"
  [[ "$found" == *user_account* ]] \
    && ok "the old image resolves again after the revert ($found)" \
    || bad "the old image resolves again after the revert" "$found"
}

# ===========================================================================
# THE CE SHAPE — sprint/186.5. Everything below drives the ONE-ROLE
# installation that init-db.sh actually creates.
# ===========================================================================

case_fresh_ce() {
  hdr "FRESH-CE — a fresh CE installation, started three times"
  reset_cluster_ce

  local attrs; attrs="$(ce_role_attrs)"
  [[ "$attrs" == "false/false/false" ]] \
    && ok "the app role stays unprivileged (super/createrole/bypassrls = $attrs)" \
    || bad "the app role must stay unprivileged" \
           "observed super/createrole/bypassrls = $attrs. BYPASSRLS on the RUNTIME role switches off row-level security for the application itself — that is the tenant isolation, not a detail."

  # The four roles the chain grants to. The app role cannot create them (it has
  # neither CREATEROLE nor BYPASSRLS), so init-db.sh must have done it, or the
  # chain dies at V6 before stage F is even reached.
  local roles; roles="$(sqla "SELECT string_agg(rolname,',' ORDER BY rolname)
                                FROM pg_roles
                               WHERE rolname IN ('kumbuka_logbook','kumbuka_memory',
                                                 'kumbuka_ops_reader','kumbuka_worklist')")"
  [[ "$roles" == "kumbuka_logbook,kumbuka_memory,kumbuka_ops_reader,kumbuka_worklist" ]] \
    && ok "init-db.sh pre-created the chain's four roles" \
    || bad "init-db.sh must pre-create the chain's four roles" "observed: ${roles:-<none>}"

  say "START 1 — the chain, as the app role"
  local out; out="$(flyway_as "$CE_ROLE" "$DB" --locations="filesystem:$MIGRATIONS" --action=migrate)"
  printf '%s\n' "$out" | grep -q 'migrate OK' \
    && ok "start 1: the chain runs to completion" \
    || bad "start 1: the chain runs to completion" "$(printf '%s' "$out" | tail -3)"

  say "the finishing step a fresh installation needs"
  out="$(KUMBUKA_DB_NAME="$DB" KUMBUKA_DB_USER="$CE_ROLE" \
         PSQL="docker exec -i $CT psql" "$FINISH_SH" 2>&1)" || true
  printf '%s\n' "$out" | sed 's/^/       /'
  [[ "$(history_in platform)" != "<absent>" && "$(history_in public)" == "<absent>" ]] \
    && ok "the history is in platform and gone from public" \
    || bad "the history is in platform" "public=$(history_in public) platform=$(history_in platform)"

  local n
  for n in 2 3; do
    out="$(flyway_as "$CE_ROLE" "$DB" --locations="filesystem:$MIGRATIONS" --action=migrate)"
    printf '%s\n' "$out" | grep -q 'migrate OK' \
      && ok "start $n: the installation starts again" \
      || bad "start $n: the installation starts again" \
             "$(printf '%s' "$out" | grep -E 'RESULT|cause' | head -2)"
  done

  say "idempotency — the finishing step a second time:"
  out="$(KUMBUKA_DB_NAME="$DB" KUMBUKA_DB_USER="$CE_ROLE" \
         PSQL="docker exec -i $CT psql" "$FINISH_SH" 2>&1)" || true
  printf '%s\n' "$out" | grep -qi 'already in platform' \
    && ok "a second run says it has nothing to do" \
    || bad "a second run says it has nothing to do" "$out"
}

case_revert_ce() {
  hdr "REVERT-CE — the revert must not strip the schema OWNER"
  reset_cluster_ce
  flyway_as "$CE_ROLE" "$DB" --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1

  local owner; owner="$(sql "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='platform'")"
  [[ "$owner" == "$CE_ROLE" ]] \
    && ok "the app role owns schema platform (it ran V21) — the shape B2 is about" \
    || bad "the app role owns schema platform" "observed owner: $owner"

  local before; before="$(as "$CE_ROLE" "SELECT count(*) FROM platform.scope_access" || true)"
  say "acl before relocate: $(sql "SELECT coalesce(nspacl::text,'<null>') FROM pg_namespace WHERE nspname='platform'")"

  step_as "$UPGRADE_SQL" "$CE_ROLE" "$DB" >/dev/null
  step_as "$REVERT_SQL"  "$CE_ROLE" "$DB" | sed 's/^/       /'
  say "acl after revert : $(sql "SELECT coalesce(nspacl::text,'<null>') FROM pg_namespace WHERE nspname='platform'")"

  local after; after="$(as "$CE_ROLE" "SELECT count(*) FROM platform.scope_access" || true)"
  if [[ "$after" == "$before" ]]; then
    ok "the view reads the same after the revert as before the relocate ($after)"
  else
    bad "the revert left the runtime role's access as it found it" \
        "before='$before' after='$after'. The revert exists to restore the pre-stage-F state; a runtime role that owns the schema was never GRANTED usage, so nothing may be revoked from it."
  fi
}

case_db_mismatch() {
  hdr "DB-MISMATCH — both scripts refuse when db is not the connected database"
  reset_cluster_ce kumbuka_prod
  sqla "CREATE DATABASE $DB OWNER $CE_ROLE" >/dev/null   # the default name exists, so the existence check passes
  flyway_as "$CE_ROLE" kumbuka_prod --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1

  local before_hist before_settings
  before_hist="$(sqlin kumbuka_prod "SELECT coalesce(to_regclass('public.flyway_schema_history')::text,'-')")"
  before_settings="$(sqla "SELECT count(*) FROM pg_db_role_setting")"

  local f r
  f="$(step_as "$UPGRADE_SQL" "$CE_ROLE" kumbuka_prod "$DB" || true)"
  printf '%s\n' "$f" | grep -q 'connected to' \
    && ok "the relocation refuses on a db mismatch" \
    || bad "the relocation refuses on a db mismatch" "$f"

  r="$(step_as "$REVERT_SQL" "$CE_ROLE" kumbuka_prod "$DB" || true)"
  printf '%s\n' "$r" | grep -q 'connected to' \
    && ok "the revert refuses on a db mismatch" \
    || bad "the revert refuses on a db mismatch" "$r"

  # A refusal that changed something is not a refusal.
  [[ "$(sqlin kumbuka_prod "SELECT coalesce(to_regclass('public.flyway_schema_history')::text,'-')")" == "$before_hist" \
     && "$(sqla "SELECT count(*) FROM pg_db_role_setting")" == "$before_settings" ]] \
    && ok "and neither run changed anything" \
    || bad "a refusal must change nothing" \
           "history and/or role settings moved: hist=$before_hist->$(sqlin kumbuka_prod "SELECT coalesce(to_regclass('public.flyway_schema_history')::text,'-')") settings=$before_settings->$(sqla "SELECT count(*) FROM pg_db_role_setting")"
}

# --- the three red probes of sprint/186.5 ----------------------------------
# Each takes the fix back out of a COPY of the artefact and shows the case go
# red. A fix whose removal changes nothing was never the fix.

case_red_fresh_ce() {
  hdr "RED — without the finishing step a fresh CE installation starts once"
  reset_cluster_ce
  flyway_as "$CE_ROLE" "$DB" --locations="filesystem:$MIGRATIONS" --action=migrate | grep -E 'RESULT' | head -1
  say "start 2, with the finishing step deliberately NOT run:"
  local out; out="$(flyway_as "$CE_ROLE" "$DB" --locations="filesystem:$MIGRATIONS" --action=migrate)"
  printf '%s\n' "$out" | grep -E 'RESULT|Found non-empty' | head -2 | sed 's/^/       /'
  printf '%s\n' "$out" | grep -q 'Found non-empty schema' \
    && ok "RED: it refuses with \"Found non-empty schema(s)\" — the step is load-bearing" \
    || bad "RED: the second start must refuse without the step" "$out"
}

case_red_revert_ce() {
  hdr "RED — the OLD revert condition strips the owner"
  reset_cluster_ce
  flyway_as "$CE_ROLE" "$DB" --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1
  # The revert as it stood before sprint/186.5: has_schema_privilege, which is
  # true for an owner too.
  local old="$WORK/revert-old.sql"
  awk '/RED-ANCHOR-BEGIN owner-safe-revoke/{skip=1;
         print "    IF has_schema_privilege(runtime, '\''platform'\'', '\''USAGE'\'') THEN"; next}
       /RED-ANCHOR-END owner-safe-revoke/{skip=0; next}
       skip{next}
       {print}' "$REVERT_SQL" > "$old"
  grep -q 'has_schema_privilege(runtime' "$old" || die "red-revert-ce: could not build the old condition"

  step_as "$UPGRADE_SQL" "$CE_ROLE" "$DB" >/dev/null
  docker exec -i "$CT" psql -U "$CE_ROLE" -d "$DB" -v ON_ERROR_STOP=1 \
    -v migrator="$CE_ROLE" -v runtime="$CE_ROLE" -v db="$DB" -q < "$old" >/dev/null 2>&1 || true
  local r; r="$(as "$CE_ROLE" "SELECT count(*) FROM platform.scope_access" || true)"
  printf '%s\n' "$r" | grep -q 'permission denied' \
    && ok "RED: with the old condition the owner loses USAGE — the fix is load-bearing" \
    || bad "RED: the old condition must strip the owner" "observed: $r"
}

case_red_db_mismatch() {
  hdr "RED — without the db check the halves come apart silently"
  reset_cluster_ce kumbuka_prod
  sqla "CREATE DATABASE $DB OWNER $CE_ROLE" >/dev/null
  flyway_as "$CE_ROLE" kumbuka_prod --locations="filesystem:$(old_chain_dir)" --action=migrate | tail -1

  # The relocation with the sprint/186.5 guard taken back out.
  local old="$WORK/relocate-nodbcheck.sql"
  awk '/^    IF db <> current_database\(\) THEN$/{skip=1}
       skip && /^    END IF;$/{skip=0; next}
       skip{next}
       {print}' "$UPGRADE_SQL" > "$old"
  grep -q 'db <> current_database' "$old" && die "red-db-mismatch: the guard is still in the copy"

  docker exec -i "$CT" psql -U "$CE_ROLE" -d kumbuka_prod -v ON_ERROR_STOP=1 \
    -v migrator="$CE_ROLE" -v runtime="$CE_ROLE" -v db="$DB" -q < "$old" 2>&1 | sed 's/^/       /'

  local moved landed
  moved="$(sqlin kumbuka_prod "SELECT coalesce(to_regclass('platform.flyway_schema_history')::text,'-')")"
  landed="$(sqlin kumbuka_prod "SELECT coalesce(string_agg(coalesce(d.datname,'<global>'),',' ORDER BY d.datname),'-')
                    FROM pg_db_role_setting s LEFT JOIN pg_database d ON d.oid=s.setdatabase
                   WHERE d.datname IS NOT NULL")"
  say "history moved in kumbuka_prod: $moved ; IN DATABASE setting landed on: $landed"
  [[ "$moved" != "-" && "$landed" == "$DB" ]] \
    && ok "RED: the history moved in one database and the setting landed on the other — silently" \
    || bad "RED: the unguarded script must split the two halves" "moved=$moved landed=$landed"
}

# ===========================================================================
main() {
  resolve_classpath
  compile_probe
  local wanted=("$@")
  [[ ${#wanted[@]} -gt 0 ]] || wanted=(fresh prod rollback window routing catalogue \
                                       revert revert-extra reforward \
                                       fresh-ce revert-ce db-mismatch \
                                       red-halfstate red-baseline red-catalogue red-routing \
                                       red-revert-v23 red-revert-path red-revert-window \
                                       red-fresh-ce red-revert-ce red-db-mismatch)
  for c in "${wanted[@]}"; do
    case "$c" in
      fresh)          case_fresh ;;
      fresh-ce)       case_fresh_ce ;;
      revert-ce)      case_revert_ce ;;
      db-mismatch)    case_db_mismatch ;;
      red-fresh-ce)   case_red_fresh_ce ;;
      red-revert-ce)  case_red_revert_ce ;;
      red-db-mismatch) case_red_db_mismatch ;;
      prod)           case_prod ;;
      rollback)       case_rollback ;;
      window)         case_window ;;
      routing)        case_routing ;;
      catalogue)      case_catalogue ;;
      red-halfstate)  case_red_halfstate ;;
      red-baseline)   case_red_baseline ;;
      red-catalogue)  case_red_catalogue ;;
      red-routing)    case_red_routing ;;
      revert)            case_revert ;;
      revert-extra)      case_revert_extra ;;
      reforward)         case_reforward ;;
      red-revert-v23)    case_red_revert_v23 ;;
      red-revert-path)   case_red_revert_path ;;
      red-revert-window) case_red_revert_window ;;
      *) die "unknown case: $c" ;;
    esac
  done
  hdr "stage-F probe: $PASS ok, $FAIL not ok, $GAP known gap(s)"
  [[ "$GAP" -eq 0 ]] || printf '\nA GAP is a measured limitation this stage cannot close. It is not a\nregression and it does not fail the run, but it must be carried into the\nreturn rather than discovered again later.\n'
  [[ "$FAIL" -eq 0 ]]
}

main "$@"
