#!/usr/bin/env bash
# ===========================================================================
# substrate.sh — the throwaway cluster every read-contract probe runs against.
#
# Sourced, never executed. It brings up a Postgres container, applies the
# migration chain out of this repository with the flyway-core version this
# module's build resolves, performs the deployment's owner-normalisation step,
# and creates the four service roles as logins a probe can connect as.
#
# WHY A PROBE CONNECTS AS THE SERVICE ROLE AND NEVER AS THE MIGRATOR
#
# A superuser is exempt from row-level security and from every privilege check,
# so a probe that queries as the migrator returns rows whether or not the grant
# it claims to witness exists. That is not a weaker test — it is a test of
# something else entirely, and it is how the V6 role defect stayed invisible.
# Every claim in this suite is therefore made through `as <role> "<sql>"`.
# ===========================================================================

HERE_SUB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_ROOT="${SERVER_ROOT:-$(cd "$HERE_SUB/../../.." && pwd)}"
MIGRATIONS="${MIGRATIONS:-$SERVER_ROOT/backend/server/src/main/resources/db/migration}"
SERVER_POM="${SERVER_POM:-$SERVER_ROOT/backend/server/pom.xml}"
PG_IMAGE="${PG_IMAGE:-postgres:16}"
PORT="${PROBE_PORT:-55466}"
CT="${PROBE_CONTAINER:-kumbuka-read-contract-probe}"
DB=kumbuka
MIGRATOR=postgres
RUNTIME=kumbuka

# The two tenants every visibility claim is made across.
TENANT_A=11111111-1111-1111-1111-111111111111
TENANT_B=22222222-2222-2222-2222-222222222222

# The subjects. ALICE and BOB are active members of tenant A, CAROL of B.
ALICE=alice-sub
BOB=bob-sub
CAROL=carol-sub

WORK="${WORK:-$(mktemp -d "${TMPDIR:-/tmp}/read-contract.XXXXXX")}"

# --- connections -----------------------------------------------------------
sqla() { docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d postgres -qtAc "$1" </dev/null; }
sql()  { docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d "$DB" -qtAc "$1" </dev/null; }
sqlq() { docker exec -i "$CT" psql -U "$MIGRATOR" -d "$DB" -qtAc "$1" </dev/null 2>&1; }
# sqld <database> <sql> — as the migrator, against a database that is not $DB.
# A shared catalogue's ACL is per-database (measured), so a grant on pg_authid
# has to be issued in the database that will be migrated, not in $DB.
sqld() { docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d "$1" -qtAc "$2" </dev/null; }
# as <role> <sql> — the only way this suite asks a question of the contract.
as()   { docker exec -i "$CT" psql -U "$1" -d "$DB" -qtAc "$2" </dev/null 2>&1; }

# --- flyway of exactly the version this build resolves ----------------------
resolve_classpath() {
  if [[ ! -s "$WORK/cp.txt" ]]; then
    mvn -B -q -f "$SERVER_POM" dependency:build-classpath \
        -Dmdep.includeScope=test -Dmdep.outputFile="$WORK/cp.txt" >/dev/null \
      || die "dependency:build-classpath failed"
  fi
  tr ':' '\n' < "$WORK/cp.txt" \
    | grep -E "/(flyway-core|flyway-database-postgresql)-[0-9][^/]*\.jar$|/org/postgresql/postgresql/[^/]*/postgresql-[^/]*\.jar$|/com/fasterxml/jackson/core/jackson-(core|databind|annotations)/|/jackson-dataformat-yaml/" \
    > "$WORK/jars.txt"
  grep -q flyway-core "$WORK/jars.txt" || die "flyway-core not on the resolved classpath"
  CP="$(paste -sd: "$WORK/jars.txt")"
}

compile_driver() {
  mkdir -p "$WORK/java"
  cat > "$WORK/java/ReadContractFlyway.java" <<'JAVA'
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import java.util.*;

public class ReadContractFlyway {
    public static void main(String[] a) {
        Map<String,String> o = new HashMap<>();
        for (String s : a) { int i = s.indexOf('='); o.put(s.substring(2, i), s.substring(i + 1)); }
        FluentConfiguration c = Flyway.configure()
            .dataSource(o.get("url"), o.get("user"), o.get("password"))
            .locations(o.get("locations").split(","))
            .outOfOrder(true);
        if (o.containsKey("target"))
            c.target(org.flywaydb.core.api.MigrationVersion.fromVersion(o.get("target")));
        Flyway f = c.load();
        try {
            System.out.println("RESULT migrate OK executed=" + f.migrate().migrationsExecuted);
        } catch (Throwable t) {
            System.out.println("RESULT FAILED " + t.getClass().getSimpleName());
            for (Throwable x = t; x != null; x = x.getCause())
                System.out.println("  cause: " + x.getClass().getSimpleName() + ": "
                    + String.valueOf(x.getMessage()).replaceAll("\\s+", " "));
        }
    }
}
JAVA
  javac -cp "$CP" -d "$WORK/java" "$WORK/java/ReadContractFlyway.java" 2>/dev/null \
    || die "could not compile the flyway driver"
}

flyway() { java -cp "$WORK/java:$CP" ReadContractFlyway \
             --url="jdbc:postgresql://localhost:$PORT/$DB" \
             --user="$MIGRATOR" --password= "$@" 2>&1 \
           | grep -vE '^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}'; }

# --- the cluster -----------------------------------------------------------
reset_cluster() {
  docker rm -f "$CT" >/dev/null 2>&1 || true
  docker run -d --name "$CT" -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_PASSWORD=probe \
    -p "$PORT":5432 "$PG_IMAGE" >/dev/null
  # The official image starts the cluster twice; both pg_isready and a plain
  # SELECT answer yes to the first one. Wait the restart out first.
  for i in $(seq 1 90); do
    docker logs "$CT" 2>&1 | grep -q 'init process complete' && break
    sleep 1; [[ $i -lt 90 ]] || die "probe container never finished initdb"; done
  for i in $(seq 1 90); do
    docker exec "$CT" psql -U "$MIGRATOR" -d postgres -qtAc 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1; [[ $i -lt 90 ]] || die "probe container never became ready"; done
  sqla "CREATE DATABASE $DB"
  sql "CREATE ROLE $RUNTIME LOGIN NOSUPERUSER NOBYPASSRLS" >/dev/null
}

# What the deployment's bootstrap does: the view only binds under a non-super
# owner, and the base tables must be owned by the runtime role.
#
# THE USAGE GRANT AT THE END IS NOT COSMETIC, AND IT IS A FINDING.
#
# V23 grants USAGE on `platform` to whoever owns `platform.scope` AT THE MOMENT
# THE MIGRATION RUNS. On an installation that was already owner-normalised
# before V23 that is the runtime role, and the grant lands correctly. On a FRESH
# database the migrator owns everything while V23 runs, the sweep re-owns the
# tables afterwards, and the runtime role is left holding tables it cannot reach
# through a name:
#
#     ERROR: permission denied for schema platform
#
# Measured 2026-09-20 on a fresh chain V1..V23 plus this sweep. Nothing in
# `deploy/bootstrap` closes it either — 14-platform-search-path.sql issues this
# grant for `kumbuka_operator` and only for it, and 10-owner-normalization.sql
# issues no grant at all. It is a V23/deploy-path defect and NOT this sprint's
# to repair (the dispatch's Grenze rules out changing V1..V23 and ops-console),
# so the substrate does here what a correct deployment has to do, and the defect
# is reported rather than silently absorbed.
# THE SAME ORDERING CAUGHT V24's EXECUTE GRANT, for the same reason. V24 reads
# the core's role out of the catalogue exactly as V23 does — the core's role IS
# the owner of the inventory — so on a fresh database that grant, too, went to
# the migrator. Measured 2026-09-20, as the runtime role on a fresh chain:
#
#     ERROR: permission denied for function tenant_id_by_alias
#
# V25 closes that half in the chain itself: it grants USAGE and EXECUTE to
# `kumbuka` by name. So on a substrate at V25 the last two statements below are
# no-ops, and they are kept for the substrates that stop earlier — `case_chain`
# migrates to V23 and calls this helper there. `case_coregrant` deliberately
# does NOT call it, because a case that ran after this helper would be green
# whether or not V25 existed.
#
# What remains a finding is the USAGE grant V23 addresses to the owner and the
# ownership move itself: both are the deployment's job, not the chain's.
owner_sweep() {
  sql "DO \$\$ DECLARE o record; BEGIN
         FOR o IN SELECT n.nspname s, c.relname r, c.relkind k FROM pg_class c
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname=ANY(ARRAY['public','platform'])
                    AND c.relkind=ANY(ARRAY['r','p','v','m']::\"char\"[])
                    AND pg_get_userbyid(c.relowner) <> '$RUNTIME' LOOP
           EXECUTE format(CASE o.k WHEN 'v' THEN 'ALTER VIEW %I.%I OWNER TO $RUNTIME'
                                   ELSE 'ALTER TABLE %I.%I OWNER TO $RUNTIME' END, o.s, o.r);
         END LOOP; END \$\$;" >/dev/null
  sql "GRANT USAGE ON SCHEMA platform TO $RUNTIME" >/dev/null
  sql "DO \$\$ BEGIN
         IF to_regprocedure('platform.tenant_id_by_alias(text)') IS NOT NULL THEN
           EXECUTE 'GRANT EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) TO $RUNTIME';
         END IF; END \$\$;" >/dev/null
}

# --- password authentication, for the one claim that needs it ---------------
#
# The container runs with POSTGRES_HOST_AUTH_METHOD=trust, so a connection that
# carries a password proves nothing: it would be accepted with the wrong one,
# and with none. A case that claims a role CAN or CANNOT authenticate has to
# turn that off first.
#
# The file is rewritten rather than patched, because the two exemptions are the
# point and a sed over the shipped lines loses them:
#   * `local`  — every other helper here connects over the unix socket, and
#                turning that into a password prompt would rewrite the suite.
#   * `host` for the MIGRATOR — Flyway connects over TCP with an EMPTY password
#                (`--password=` in the driver), so demanding one of it fails the
#                migration before the case reaches its claim. Measured: "The
#                server requested SCRAM-based authentication, but the password
#                is an empty string."
# What is left needing a password is exactly what the cases ask about: a service
# role over TCP.
#
# The method is `md5` rather than `scram-sha-256`, and that is not laxity: `md5`
# accepts EITHER verifier — Postgres negotiates SCRAM by itself when the role
# carries a SCRAM one — whereas a `scram-sha-256` line refuses a role with an
# MD5 verifier before any password is checked. A case about what a rename does
# to an MD5 password has to be able to log that role in first, so the line has
# to admit both.
# The file's location is ASKED FOR, never assumed: postgres:16 keeps it in
# /var/lib/postgresql/data and postgres:18 does not, so a hardcoded path writes
# a file the server never reads — and the suite then runs under trust while
# reporting on passwords. Measured on postgres:18.6, where exactly one claim
# went red and the rest stayed green for the wrong reason.
#
# And the result is verified rather than trusted: a rewrite that does not take
# leaves every authentication claim vacuously true, which is the failure this
# helper exists to remove.
require_password_auth() {
  local hba; hba="$(sql 'SHOW hba_file')"
  [[ -n "$hba" ]] || die "could not ask the server where pg_hba.conf is"
  docker exec -u postgres -i "$CT" bash -c \
    "printf '%s\n' 'local all all trust' \
                   'host all $MIGRATOR all trust' \
                   'host all all all md5' \
       > '$hba'" \
    || die "could not rewrite $hba in the probe container"
  sql "SELECT pg_reload_conf()" >/dev/null
  [[ "$(sql "SELECT count(*) FROM pg_hba_file_rules WHERE auth_method='md5'")" == "1" ]] \
    || die "the reloaded $hba does not carry the md5 rule — every password claim below it would be vacuous"
}

# Can <role> log in over TCP with <password>? Answers `yes` or `no` — never the
# raw psql error, so a case can assert on it. Requires require_password_auth.
can_authenticate() {   # can_authenticate <role> <password>
  local out
  out="$(docker exec -e PGPASSWORD="$2" -i "$CT" \
           psql -h 127.0.0.1 -U "$1" -d "$DB" -qtAc "SELECT 1" </dev/null 2>&1)"
  [[ "$out" == "1" ]] && printf 'yes' || printf 'no'
}

# The chain as an installation at V23 carries it — used to prove V24 is the
# only thing this dispatch adds.
chain_dir_through() {   # chain_dir_through <max version>
  # Two statements, not `local max="$1" d="…$max"`: bash expands every word of a
  # `local` line before the builtin assigns any of them, so the second would
  # have read an unset `max` (and, under `set -u`, aborted the case with an
  # empty migration directory and a green `executed=0`).
  local max="$1"
  local d="$WORK/chain-$max" f v
  rm -rf "$d"; mkdir -p "$d"
  for f in "$MIGRATIONS"/V*.sql; do
    v="$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')"
    [[ "$v" -le "$max" ]] && cp "$f" "$d"/
  done
  printf '%s' "$d"
}

# The whole chain, copied, with ONE marked block cut out of ONE migration — the
# shape every red probe needs whose subject is the migration's own act rather
# than a state that could be staged in the database afterwards.
#
# A pair of marker comments in the migration delimits the block, so the removal
# is exact and a later edit to it cannot silently leave half of it standing.
# The caller checks that the copy really lost the lines: an earlier version of
# V24's markers ended the deleted range inside its own opening comment, so the
# markers went and the guard stayed — and the probe reported it removed.
#
#   chain_dir_without_block <marker base> <migration glob>
chain_dir_without_block() {
  local d="$WORK/chain-no-$1" f
  rm -rf "$d"; mkdir -p "$d"
  for f in "$MIGRATIONS"/V*.sql; do cp "$f" "$d"/; done
  sed -i.bak "/$1-begin/,/$1-end/d" "$d"/$2
  rm -f "$d"/*.bak
  printf '%s' "$d"
}

chain_dir_without_md5_guard() { chain_dir_without_block md5-guard 'V24__*.sql'; }

# --- the test population ---------------------------------------------------
#
# Two tenants. Tenant A: alice (active admin) and bob (active member); an open
# project scope, a locked project scope, an archived project scope, the tenant's
# singleton private scope and its singleton global scope. Tenant B: carol, one
# project scope — the row no query in this suite may ever return for A.
#
# Written as the migrator, which is exempt from RLS. That is legitimate HERE:
# seeding is not a claim. Every CLAIM is made as a service role.
seed_population() {
  sql "INSERT INTO platform.team (tenant_id, name, alias) VALUES
         ('$TENANT_A','Alpha','alpha'), ('$TENANT_B','Beta','beta')" >/dev/null
  sql "INSERT INTO platform.user_account (tenant_id, subject, email, role, status, muted) VALUES
         ('$TENANT_A','$ALICE','alice@alpha.test','admin','active',false),
         ('$TENANT_A','$BOB','bob@alpha.test','member','active',false),
         ('$TENANT_B','$CAROL','carol@beta.test','member','active',false)" >/dev/null
  sql "INSERT INTO platform.scope (tenant_id, name, slug, kind, archived, locked, fixed, created_by) VALUES
         ('$TENANT_A','open-project','open-project','project',false,false,false,'$ALICE'),
         ('$TENANT_A','locked-project','locked-project','project',false,true,false,'$ALICE'),
         ('$TENANT_A','archived-project','archived-project','project',true,false,false,'$ALICE'),
         ('$TENANT_A','private','private','private',false,false,false,null),
         ('$TENANT_A','global','global','global',false,false,true,null),
         ('$TENANT_B','beta-project','beta-project','project',false,false,false,'$CAROL')" >/dev/null
}

# A query against the contract, bound the way a service binds it: both settings
# transaction-local, fail-closed, in ONE transaction with the SELECT.
#
# The two settings go through a DO block rather than two `SELECT set_config(…)`
# statements, so the only rows psql prints are the query's own. With SELECTs the
# empty-string binding (the fail-closed case) printed two blank lines that are
# indistinguishable from an empty result, and an offset that skipped them
# swallowed a real `0` — a helper that turned a passing case into a failing one.
bound()  {   # bound <role> <tenant> <subject> <sql>
  as "$1" "BEGIN;
           DO \$b\$ BEGIN
             PERFORM set_config('app.tenant_id','$2',true);
             PERFORM set_config('app.subject','$3',true);
           END \$b\$;
           $4;
           COMMIT;" 2>&1 | grep -vxE 'BEGIN|COMMIT|DO'
}
