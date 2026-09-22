#!/usr/bin/env bash
# ===========================================================================
# read-contract-probe.sh — witnesses V24 against a throwaway Postgres, with a
# RED counter-probe for every claim it makes.
#
# EVERY CLAIM IS MADE AS THE SERVICE ROLE THAT WOULD MAKE IT IN PRODUCTION.
# Never as the migrator and never as a superuser: a superuser is exempt from
# row-level security and from every privilege check, so the same query answers
# yes whether or not the grant exists. Case `red-superuser` measures that
# directly — it removes a grant, runs the probe as the superuser, watches it
# stay green, then runs the same query as the service role and watches it go
# red. That contrast is the reason for the rule.
#
# Cases
#   resolution   A2 — platform.tenant_id_by_alias as each of the four roles
#   visibility   A3 — kind, lock and tenant boundary, as each service role
#   writeright   A4 — can_write against MemberWritePolicy's decision table
#   rolename     A5 — kumbuka_logbook becomes kumbuka_dispatch, keeps its
#                     privileges and its password; and the collision case
#   md5guard     A1/A2 — the rename is refused where it would DELETE the
#                     password (an MD5 verifier), and goes through once it
#                     would not; and the migrator that may not look
#   migrator     the whole chain under a CREATEROLE non-superuser migrator
#   coregrant    K1 — V25: the core's runtime role `kumbuka` holds the lookup
#                     by name, on a database where nothing owner-normalised it
#   chain        A6 — V1..V23 unchanged, V24 applies on top, the view's first
#                     four columns keep name, type and position
#
# Red probes (each expected to fail where the green run passes)
#   red-memory-grant  R1  the grant to kumbuka_memory removed  -> A2 red
#   red-author        R2  the private-scope author check removed -> A3 red
#   red-tenant        R3  the tenant predicate removed         -> A3 red
#   red-superuser     R4  the same removed grant, probed as superuser: GREEN;
#                         then as the service role: RED
#   red-resolver      the alias policy removed                 -> A2 red
#   red-md5guard      R1  the MD5 guard cut out of V24: the rename applies and
#                         the service role loses the password it logs in with
#   red-core-grant    K1  V25's grant block cut out: the core is refused the
#                         alias lookup — the state the finding recorded
#   red-core-verify   K1  V25's grant cut but its read-back kept: the migration
#                         refuses to finish rather than reporting a grant it
#                         did not issue
#
# Usage
#   ./read-contract-probe.sh            # every case
#   ./read-contract-probe.sh resolution # selected
#   KEEP=1 ./read-contract-probe.sh     # leave the container up
# ===========================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
die() { printf 'PROBE ABORTED: %s\n' "$*" >&2; exit 1; }
# shellcheck source=substrate.sh
source "$HERE/substrate.sh"

KEEP="${KEEP:-}"
PASS=0; FAIL=0
cleanup() { [[ -n "$KEEP" ]] || docker rm -f "$CT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

hdr() { printf '\n======================================================================\n%s\n======================================================================\n' "$*"; }
say() { printf -- '--- %s\n' "$*"; }
ok()  { PASS=$((PASS+1)); printf 'ok     - %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf 'NOT OK - %s\n         %s\n' "$1" "${2:-}"; }
is()  { [[ "$2" == "$3" ]] && ok "$1" || bad "$1" "expected [$3], got [$2]"; }

command -v docker >/dev/null || die "docker not on PATH"
command -v mvn    >/dev/null || die "mvn not on PATH"
command -v javac  >/dev/null || die "javac not on PATH"
[[ -d "$MIGRATIONS" ]] || die "migration directory not found: $MIGRATIONS"

# --- a database at V24, seeded, with the production owner shape -------------
prepare() {
  reset_cluster
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  owner_sweep
  seed_population
}

# The columns of platform.scope_access, as a name:type:position string, read out
# of the catalogue. A6 compares this before and after V24 — "appended, never
# reshuffled" is a fact about the catalogue, not a promise in a comment.
view_shape() {
  sql "SELECT string_agg(column_name||':'||data_type||':'||ordinal_position, ',' ORDER BY ordinal_position)
         FROM information_schema.columns
        WHERE table_schema='platform' AND table_name='scope_access'"
}

# BOTH shapes are written out here, and NEITHER is read back out of the database
# first. A6 exists to catch V24 reshuffling what V21 published; an expectation
# taken from the database that V24 just migrated cannot catch that, because a
# reshuffle would move the expectation with it. The measured form was compared
# against the source of both migrations, which is where these two lines come
# from and where a reader can check them:
#
#   V21__platform_tenancy_directory.sql:76   scope_id, tenant_id, slug, archived
#   V1__init.sql:39 / V2:12 / V16:258        scope.kind VARCHAR(16), slug TEXT,
#                                            archived BOOLEAN, locked BOOLEAN
#   V24__platform_read_contract.sql:391      kind, locked, can_write appended
#
# Until 2026-09-20 the second claim compared V24's form against the V23 form
# READ FROM THE SAME DATABASE moments earlier, which is review criterion 2: the
# expectation came out of the artefact under test.
V23_VIEW_SHAPE='scope_id:uuid:1,tenant_id:uuid:2,slug:text:3,archived:boolean:4'
V24_VIEW_SHAPE="$V23_VIEW_SHAPE,kind:character varying:5,locked:boolean:6,can_write:boolean:7"

# ===========================================================================
case_resolution() {
  hdr "A2 — platform.tenant_id_by_alias, as each role that holds EXECUTE"
  prepare

  local r
  for r in kumbuka kumbuka_worklist kumbuka_dispatch kumbuka_memory; do
    is "$r resolves the known alias 'alpha'" \
       "$(as "$r" "SELECT platform.tenant_id_by_alias('alpha')")" "$TENANT_A"
    is "$r resolves the known alias 'beta'" \
       "$(as "$r" "SELECT platform.tenant_id_by_alias('beta')")" "$TENANT_B"
    is "$r gets NULL for an unknown alias" \
       "$(as "$r" "SELECT coalesce(platform.tenant_id_by_alias('nosuch')::text,'<NULL>')")" "<NULL>"
  done

  say "a role with no grant, created for this probe"
  sql "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='probe_outsider')
         THEN CREATE ROLE probe_outsider LOGIN NOSUPERUSER NOBYPASSRLS; END IF; END \$\$;" >/dev/null
  local denied; denied="$(as probe_outsider "SELECT platform.tenant_id_by_alias('alpha')")"
  case "$denied" in
    *"permission denied"*) ok "an ungranted role is refused (42501)";;
    *) bad "an ungranted role is refused" "$denied";;
  esac

  say "and the resolver widens the alias lookup and NOTHING else"
  is "the core still sees no team row directly (RLS unchanged)" \
     "$(as kumbuka "SELECT count(*) FROM platform.team")" "0"
  local t; t="$(as kumbuka_worklist "SELECT count(*) FROM platform.team")"
  case "$t" in
    *"permission denied"*) ok "a service role still cannot read platform.team at all";;
    *) bad "a service role still cannot read platform.team" "$t";;
  esac
  local sr; sr="$(as kumbuka "SET ROLE kumbuka_alias_resolver")"
  case "$sr" in
    *"permission denied"*) ok "the core cannot SET ROLE to the resolver";;
    *) bad "the core cannot SET ROLE to the resolver" "$sr";;
  esac
  is "the resolver cannot log in" \
     "$(sql "SELECT rolcanlogin FROM pg_roles WHERE rolname='kumbuka_alias_resolver'")" "f"
  is "the resolver is not BYPASSRLS" \
     "$(sql "SELECT rolbypassrls FROM pg_roles WHERE rolname='kumbuka_alias_resolver'")" "f"
}

# ===========================================================================
case_visibility() {
  hdr "A3 — what each service role sees, bound to tenant A as bob (a member)"
  prepare

  local r
  for r in kumbuka_worklist kumbuka_dispatch kumbuka_memory; do
    is "$r: the scopes of tenant A, by kind" \
       "$(bound "$r" "$TENANT_A" "$BOB" \
            "SELECT string_agg(slug||'/'||kind, ',' ORDER BY slug) FROM platform.scope_access")" \
       "archived-project/project,global/global,locked-project/project,open-project/project,private/private"

    is "$r: the global scope is there" \
       "$(bound "$r" "$TENANT_A" "$BOB" \
            "SELECT count(*) FROM platform.scope_access WHERE kind='global'")" "1"

    is "$r: the locked project carries locked = true" \
       "$(bound "$r" "$TENANT_A" "$BOB" \
            "SELECT locked FROM platform.scope_access WHERE slug='locked-project'")" "t"

    is "$r: the open project carries locked = false" \
       "$(bound "$r" "$TENANT_A" "$BOB" \
            "SELECT locked FROM platform.scope_access WHERE slug='open-project'")" "f"

    is "$r: no scope of the other tenant, ever" \
       "$(bound "$r" "$TENANT_A" "$BOB" \
            "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")" "0"

    is "$r: carol's tenant is not reachable by binding to it without membership" \
       "$(bound "$r" "$TENANT_B" "$BOB" "SELECT count(*) FROM platform.scope_access")" "0"

    is "$r: unbound settings answer nothing (fail-closed)" \
       "$(bound "$r" "" "" "SELECT count(*) FROM platform.scope_access")" "0"
  done

  say "an AUTHORED private scope belongs to its author alone"
  # Measured in step 0: uq_scope_one_private allows only ONE private scope per
  # tenant, so an authored one can only be staged by giving the tenant's single
  # private scope an author. That is the shape the check has to bind on.
  sql "UPDATE platform.scope SET created_by='$ALICE' WHERE tenant_id='$TENANT_A' AND kind='private'" >/dev/null
  is "alice sees her own private scope" \
     "$(bound kumbuka_worklist "$TENANT_A" "$ALICE" \
          "SELECT count(*) FROM platform.scope_access WHERE kind='private'")" "1"
  is "bob does NOT see alice's private scope" \
     "$(bound kumbuka_worklist "$TENANT_A" "$BOB" \
          "SELECT count(*) FROM platform.scope_access WHERE kind='private'")" "0"
  is "bob still sees the shared scopes" \
     "$(bound kumbuka_worklist "$TENANT_A" "$BOB" \
          "SELECT count(*) FROM platform.scope_access WHERE kind<>'private'")" "4"
  sql "UPDATE platform.scope SET created_by=NULL WHERE tenant_id='$TENANT_A' AND kind='private'" >/dev/null

  say "a disabled member is not an active member"
  sql "UPDATE platform.user_account SET status='disabled' WHERE subject='$BOB'" >/dev/null
  is "a disabled member sees nothing" \
     "$(bound kumbuka_worklist "$TENANT_A" "$BOB" "SELECT count(*) FROM platform.scope_access")" "0"
  sql "UPDATE platform.user_account SET status='active' WHERE subject='$BOB'" >/dev/null
}

# ===========================================================================
case_writeright() {
  hdr "A4 — can_write against MemberWritePolicy's decision, combination by combination"
  prepare

  # The expectation comes from MemberWritePolicy as a specification, NOT from
  # the view: assertCanWriteShared refuses a muted member on a scope that is not
  # the private one; assertScopeWritable refuses every service-channel write to
  # a locked scope. The console admin override is not representable here and is
  # reported as a finding — every MCP call site passes callerIsAdmin=false.
  #
  #   kind      muted  locked  expected can_write
  #   project   f      f       t
  #   project   t      f       f
  #   project   f      t       f
  #   project   t      t       f
  #   global    f      f       t
  #   global    t      f       f
  #   private   f      f       t
  #   private   t      f       t     <- muted does not reach a private scope
  #   private   f      t       f
  #   private   t      t       f
  # Held in an array rather than read from a heredoc: the psql helpers run
  # `docker exec -i`, which would consume the loop's stdin and cut the table
  # off after its first row — a suite that ran one combination and reported
  # green.
  local row kind muted locked expect slug got
  for row in \
    "project false false t" "project true  false f" \
    "project false true  f" "project true  true  f" \
    "global  false false t" "global  true  false f" \
    "private false false t" "private true  false t" \
    "private false true  f" "private true  true  f"
  do
    read -r kind muted locked expect <<<"$row"
    case "$kind" in
      project) slug=open-project;;
      global)  slug=global;;
      private) slug=private;;
    esac
    sql "UPDATE platform.user_account SET muted=$muted WHERE subject='$BOB'" >/dev/null
    sql "UPDATE platform.scope SET locked=$locked WHERE tenant_id='$TENANT_A' AND slug='$slug'" >/dev/null
    got="$(bound kumbuka_dispatch "$TENANT_A" "$BOB" \
             "SELECT can_write FROM platform.scope_access WHERE slug='$slug'")"
    is "kind=$kind muted=$muted locked=$locked -> can_write=$expect" "$got" "$expect"
  done
  sql "UPDATE platform.user_account SET muted=false WHERE subject='$BOB'" >/dev/null
  sql "UPDATE platform.scope SET locked=false WHERE tenant_id='$TENANT_A' AND slug<>'locked-project'" >/dev/null
}

# ===========================================================================
case_rolename() {
  hdr "A5 — kumbuka_logbook becomes kumbuka_dispatch, and keeps what it held"

  say "the ordinary case: V23 first, then V24 on top"
  reset_cluster
  flyway --locations="filesystem:$(chain_dir_through 23)" --action=migrate | tail -1
  owner_sweep
  sql "ALTER ROLE kumbuka_logbook PASSWORD 'dispatch-secret'" >/dev/null
  local before_verb
  before_verb="$(sql "SELECT CASE WHEN rolpassword LIKE 'SCRAM-SHA-256%' THEN 'SCRAM-SHA-256'
                                  WHEN rolpassword LIKE 'md5%' THEN 'MD5' ELSE 'other' END
                        FROM pg_authid WHERE rolname='kumbuka_logbook'")"
  is "before V24 the role's password verb is SCRAM (an MD5 verb would not survive a rename)" \
     "$before_verb" "SCRAM-SHA-256"

  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  is "kumbuka_logbook is gone" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname='kumbuka_logbook'")" "0"
  is "kumbuka_dispatch is there" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname='kumbuka_dispatch'")" "1"
  is "it still holds SELECT on the view" \
     "$(sql "SELECT count(*) FROM information_schema.role_table_grants
               WHERE table_schema='platform' AND table_name='scope_access'
                 AND grantee='kumbuka_dispatch' AND privilege_type='SELECT'")" "1"
  is "it still holds USAGE on the schema" \
     "$(sql "SELECT has_schema_privilege('kumbuka_dispatch','platform','USAGE')")" "t"
  is "its password verb survived the rename" \
     "$(sql "SELECT CASE WHEN rolpassword LIKE 'SCRAM-SHA-256%' THEN 'SCRAM-SHA-256' ELSE 'other' END
               FROM pg_authid WHERE rolname='kumbuka_dispatch'")" "SCRAM-SHA-256"

  # It authenticates with that password — trust auth would prove nothing, so
  # this one connection goes through md5/scram by asking for a password
  # explicitly over TCP from inside the container.
  local auth
  auth="$(docker exec -e PGPASSWORD=dispatch-secret -i "$CT" \
            psql -h 127.0.0.1 -U kumbuka_dispatch -d "$DB" -qtAc "SELECT current_user" 2>&1)"
  is "it authenticates as itself with its own password" "$auth" "kumbuka_dispatch"

  say "the collision case: a cluster where the dispatch service already created its role"
  reset_cluster
  flyway --locations="filesystem:$(chain_dir_through 23)" --action=migrate | tail -1
  owner_sweep
  sql "CREATE ROLE kumbuka_dispatch LOGIN PASSWORD 'already-here'" >/dev/null
  local out; out="$(flyway --locations="filesystem:$MIGRATIONS" --action=migrate)"
  case "$out" in
    *"migrate OK"*) ok "V24 applies rather than stopping the container";;
    *) bad "V24 applies in the collision case" "$out";;
  esac
  is "both roles are present, and the rename was skipped" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname IN ('kumbuka_logbook','kumbuka_dispatch')")" "2"
  is "the grants went to kumbuka_dispatch anyway" \
     "$(sql "SELECT has_schema_privilege('kumbuka_dispatch','platform','USAGE')::text||'/'||
                    has_table_privilege('kumbuka_dispatch','platform.scope_access','SELECT')::text")" \
     "true/true"
}

# ===========================================================================
# A1/A2 — the guard in front of the rename.
#
# Renaming a role DELETES an MD5 password verifier: it is salted with the role
# name. What that leaves behind is a service role that still holds every
# privilege and can no longer authenticate, after a migration that reported
# success — and the production window has no way back but an image swap.
#
# Both halves are measured on ONE database, in the order an operator would meet
# them: the migration refuses, the operator does what the message says, the
# migration goes through.
case_md5guard() {
  hdr "A1/A2 — V24 refuses to rename a role whose password is an MD5 verifier"

  reset_cluster
  require_password_auth
  flyway --locations="filesystem:$(chain_dir_through 23)" --action=migrate | tail -1
  owner_sweep

  say "a database at V23 whose kumbuka_logbook carries an MD5 password"
  sql "SET password_encryption='md5'; ALTER ROLE kumbuka_logbook PASSWORD 'logbook-secret'" >/dev/null
  is "the verifier is MD5 to begin with" \
     "$(sql "SELECT CASE WHEN rolpassword LIKE 'md5%' THEN 'MD5' ELSE 'other' END
               FROM pg_authid WHERE rolname='kumbuka_logbook'")" "MD5"
  is "and the role can authenticate with it" \
     "$(can_authenticate kumbuka_logbook logbook-secret)" "yes"

  local out; out="$(flyway --locations="filesystem:$MIGRATIONS" --action=migrate)"
  case "$out" in
    *"migrate OK"*) bad "A1: V24 refuses the rename" "it applied: $(printf '%s' "$out" | tr '\n' ' ')";;
    *) ok "A1: V24 stops rather than renaming";;
  esac
  case "$out" in
    *kumbuka_logbook*) ok "the message names the role";;
    *) bad "the message names the role" "$(printf '%s' "$out" | tr '\n' ' ')";;
  esac
  case "$out" in
    *scram-sha-256*) ok "and says what to do about it (set the password under SCRAM)";;
    *) bad "the message says what to do" "$(printf '%s' "$out" | tr '\n' ' ')";;
  esac

  say "and the database is untouched — Postgres rolls DDL back, so V24 left no trace"
  is "the flyway head is still 23" \
     "$(sql "SELECT max(version::numeric) FROM flyway_schema_history WHERE success")" "23"
  is "no failed V24 row was left behind either" \
     "$(sql "SELECT count(*) FROM flyway_schema_history WHERE version='24'")" "0"
  is "the role still has its old name" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname='kumbuka_logbook'")" "1"
  is "kumbuka_dispatch was not created" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname='kumbuka_dispatch'")" "0"
  is "the password is still there, and still works" \
     "$(can_authenticate kumbuka_logbook logbook-secret)" "yes"
  is "the view is still the V23 one" "$(view_shape)" "$V23_VIEW_SHAPE"

  say "A2 — the operator does what the message said; the same migration now applies"
  sql "SET password_encryption='scram-sha-256'; ALTER ROLE kumbuka_logbook PASSWORD 'logbook-secret'" >/dev/null
  local out2; out2="$(flyway --locations="filesystem:$MIGRATIONS" --action=migrate)"
  case "$out2" in
    *"migrate OK"*) ok "A2: with a SCRAM verifier V24 applies as it always did";;
    *) bad "A2: V24 applies once the verifier is SCRAM" "$(printf '%s' "$out2" | tr '\n' ' ')";;
  esac
  is "the rename happened" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname='kumbuka_dispatch'")" "1"
  is "and the password came with it" \
     "$(can_authenticate kumbuka_dispatch logbook-secret)" "yes"

  # The other half of the guard: a migrator that cannot read the catalogue is
  # refused too, because a rename it cannot check is the same rename. The
  # deployment migrates the core as the superuser
  # (infra/compose.prod.yml: QUARKUS_FLYWAY_USERNAME: ${POSTGRES_USER}), so
  # this is the stage-F shape, not today's.
  say "a migrator that may not read pg_authid is refused as well"
  reset_cluster
  sql "CREATE ROLE unprivileged_migrator LOGIN CREATEROLE NOSUPERUSER BYPASSRLS" >/dev/null
  sqla "CREATE DATABASE kumbuka_unprivileged OWNER unprivileged_migrator" >/dev/null
  local u_url="jdbc:postgresql://localhost:$PORT/kumbuka_unprivileged"
  local u_out
  u_out="$(java -cp "$WORK/java:$CP" ReadContractFlyway --url="$u_url" \
             --user=unprivileged_migrator --password= \
             --locations="filesystem:$MIGRATIONS" --action=migrate 2>&1 \
           | grep -vE '^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}')"
  case "$u_out" in
    *"cannot read pg_catalog.pg_authid"*) ok "it stops, and says which read it is missing";;
    *) bad "an unprivileged migrator is refused the rename" "$(printf '%s' "$u_out" | tr '\n' ' ')";;
  esac
  case "$u_out" in
    *"GRANT SELECT ON pg_catalog.pg_authid"*) ok "and names the grant that would let it through";;
    *) bad "the message names the grant" "$(printf '%s' "$u_out" | tr '\n' ' ')";;
  esac

  say "with that one grant — in the database being migrated — the same migrator gets through"
  sqld kumbuka_unprivileged "GRANT SELECT ON pg_catalog.pg_authid TO unprivileged_migrator" >/dev/null
  local g_out
  g_out="$(java -cp "$WORK/java:$CP" ReadContractFlyway --url="$u_url" \
             --user=unprivileged_migrator --password= \
             --locations="filesystem:$MIGRATIONS" --action=migrate 2>&1 \
           | grep -vE '^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}')"
  case "$g_out" in
    *"migrate OK"*) ok "the grant is the whole of what stage F needs here";;
    *) bad "the granted migrator applies the chain" "$(printf '%s' "$g_out" | tr '\n' ' ')";;
  esac
}

# ===========================================================================
# R1 — take the guard out and watch the damage happen.
#
# This is the one red probe that cannot be staged by editing the database
# afterwards: the guard is IN the migration, and what it prevents is the
# migration's own act. So the chain is copied with the marked block cut out.
case_red_md5guard() {
  hdr "R1 — remove the MD5 guard: the rename goes through and takes the password"

  local nochain; nochain="$(chain_dir_without_md5_guard)"
  # Both markers gone, and the guard's own statement with them. The first check
  # alone was not enough: an earlier version of the markers ended the deleted
  # range inside its own opening comment, so the markers went and the guard
  # stayed — and the probe said it had been removed.
  is "the copy lost both markers" \
     "$(grep -c 'md5-guard' "$nochain"/V24__*.sql)" "0"
  is "and the guard's check went with them" \
     "$(grep -c 'has_table_privilege' "$nochain"/V24__*.sql)" "0"

  reset_cluster
  require_password_auth
  flyway --locations="filesystem:$(chain_dir_through 23)" --action=migrate | tail -1
  owner_sweep
  sql "SET password_encryption='md5'; ALTER ROLE kumbuka_logbook PASSWORD 'logbook-secret'" >/dev/null
  is "green first: the role authenticates with its MD5 password" \
     "$(can_authenticate kumbuka_logbook logbook-secret)" "yes"

  local out; out="$(flyway --locations="filesystem:$nochain" --action=migrate)"
  case "$out" in
    *"migrate OK"*) ok "RED: without the guard the migration reports success";;
    *) bad "RED: without the guard the migration applies" "$(printf '%s' "$out" | tr '\n' ' ')";;
  esac
  is "RED: the verifier is gone" \
     "$(sql "SELECT coalesce(rolpassword,'<NULL>') FROM pg_authid WHERE rolname='kumbuka_dispatch'")" \
     "<NULL>"
  is "RED: and the dispatch service can no longer authenticate" \
     "$(can_authenticate kumbuka_dispatch logbook-secret)" "no"
  say "a clean migration log, every privilege intact, and a service that cannot log in."
  say "that silence is what the guard in V24 exists to prevent."
}

# ===========================================================================
# The case this suite was missing, and the reason it was missing it.
#
# Every other case here migrates as `postgres`. A superuser is exempt from the
# check that a new owner must hold CREATE on its object's schema, so the whole
# suite stayed green while V24's ownership transfer was refused for the migrator
# stage F actually intends:
#
#     ALTER FUNCTION platform.tenant_id_by_alias(text) OWNER TO kumbuka_alias_resolver
#     ERROR:  permission denied for schema platform
#
# It was `MigrationCallbackWitnessIT` — which migrates as a CREATEROLE
# non-superuser — that turned red. This case brings that shape here, so the
# chain is witnessed under the privilege it will really run with.
case_migrator() {
  hdr "the chain under the migrator stage F intends: CREATEROLE, NOT a superuser"
  reset_cluster
  sql "CREATE ROLE stage_f_migrator LOGIN CREATEROLE NOSUPERUSER BYPASSRLS" >/dev/null
  sqla "CREATE DATABASE $DB'_stagef' OWNER stage_f_migrator" >/dev/null 2>&1 || \
    sqla "CREATE DATABASE kumbuka_stagef OWNER stage_f_migrator" >/dev/null
  # V24's MD5 guard reads pg_catalog.pg_authid, and a CREATEROLE non-superuser
  # may not — measured, and it cannot grant itself the read either (a predefined
  # role's ADMIN option belongs to the superuser). So a stage-F migrator needs
  # this one grant, which is what the guard's own message asks for;
  # `case_md5guard` witnesses both halves of that. It is issued HERE, in the
  # database about to be migrated, because a shared catalogue's ACL is
  # per-database. Today's deployment is unaffected: it migrates as the superuser.
  sqld kumbuka_stagef "GRANT SELECT ON pg_catalog.pg_authid TO stage_f_migrator" >/dev/null

  # BYPASSRLS only while the chain applies (V6 hands it out and only a holder
  # may); the ownership question this case is about is unaffected by it.
  local url="jdbc:postgresql://localhost:$PORT/kumbuka_stagef"
  local out
  out="$(java -cp "$WORK/java:$CP" ReadContractFlyway --url="$url" \
           --user=stage_f_migrator --password= \
           --locations="filesystem:$MIGRATIONS" --action=migrate 2>&1 \
         | grep -vE '^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}')"
  case "$out" in
    *"migrate OK"*) ok "the whole chain, V24 included, applies as a non-superuser migrator";;
    *) bad "the chain applies as a non-superuser migrator" "$(printf '%s' "$out" | tr '\n' ' ')";;
  esac

  local owner
  owner="$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
             "SELECT pg_get_userbyid(p.proowner) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname='platform' AND p.proname='tenant_id_by_alias'" </dev/null 2>&1)"
  is "the function is owned by the resolver, not by the migrator" \
     "$owner" "kumbuka_alias_resolver"

  local create_priv
  create_priv="$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
                   "SELECT has_schema_privilege('kumbuka_alias_resolver','platform','CREATE')::text
                        ||'/'||has_schema_privilege('kumbuka_alias_resolver','platform','USAGE')::text" </dev/null 2>&1)"
  is "and the CREATE it borrowed for the transfer was handed back (create/usage)" \
     "$create_priv" "false/true"

  # --- the finding this case also carries -----------------------------------
  #
  # On a FRESH database the migrator owns the inventory while the chain runs, so
  # every grant the chain addresses to "whoever owns the inventory" is addressed
  # to the MIGRATOR rather than to the runtime role. V23 does it for USAGE on
  # the schema; V24 did it for EXECUTE on the lookup, because it read the core's
  # role the same way and the chain had no other way to name a role whose name
  # differed by installation.
  #
  # V25 closes the EXECUTE half AT THE CHAIN: the core's runtime role is called
  # `kumbuka` in every installation, so the grant is issued to that name and
  # does not depend on who owns anything. The USAGE half of V23 is still the
  # deployment's, and still unclosed — 10-owner-normalization.sql issues no
  # grant at all, and 14-platform-search-path.sql issues USAGE for
  # `kumbuka_operator` only. V25 grants USAGE to `kumbuka` beside its EXECUTE,
  # which covers the one role the core connects as and nothing else.
  #
  # The ownership state itself stays what it is, and is measured rather than
  # repaired: an owner sweep is the deployment's act, and this sprint's Grenze
  # rules out touching V23 and ops-console.
  say "the fresh-install ownership state, which V25 no longer depends on"
  local who
  who="$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
           "SELECT pg_get_userbyid(c.relowner) FROM pg_class c
              JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='platform' AND c.relname='scope'" </dev/null 2>&1)"
  is "on a fresh chain the inventory is owned by the MIGRATOR, not the runtime role" \
     "$who" "stage_f_migrator"
  # --- and the second finding, which only this migrator can show --------------
  #
  # V24 hands the function to `kumbuka_alias_resolver` BEFORE it grants EXECUTE
  # on it, so under a migrator that is not a superuser the grant — and the
  # REVOKE FROM PUBLIC above it — are issued by a role that does not own the
  # object. Postgres does not refuse that. It warns and does nothing:
  #
  #     GRANT EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) TO kumbuka
  #     WARNING:  no privileges were granted for "tenant_id_by_alias"
  #
  # Measured 2026-09-22 on the chain at V24 under this very shape: the access
  # list reads {=X/resolver, resolver=X/resolver} — the owner's entry and
  # PUBLIC's default, and NOT ONE of the four grantees V24 names. Every one of
  # them can still call the lookup, which is why the defect is invisible to a
  # probe that asks has_function_privilege: that answers true for all of them,
  # and would answer true for any role in the cluster holding USAGE on the
  # schema.
  #
  # Until 2026-09-22 this case asserted exactly that has_function_privilege was
  # true and read it as "V24's grant landed on the migrator". It did not; PUBLIC
  # did. Reported as a finding against V24, which this sprint may not edit.
  say "V24's grants on the lookup, issued by a migrator that no longer owns it"
  is "V24's EXECUTE grant landed on NO named role — not even on the inventory's owner" \
     "$(execute_acl_in kumbuka_stagef stage_f_migrator stage_f_migrator)" "f"
  is "nor on the service roles V24 names literally" \
     "$(execute_acl_in kumbuka_stagef stage_f_migrator kumbuka_memory)" "f"
  is "and V24's REVOKE FROM PUBLIC did not take either — every role still holds EXECUTE" \
     "$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
          "SELECT has_function_privilege('public','platform.tenant_id_by_alias(text)','EXECUTE')" \
        </dev/null 2>&1)" "t"

  # V25 is issued under the function's OWNER for exactly this reason, and reads
  # the ACL back rather than trusting a migration that reported success.
  say "V25, under the same migrator"
  is "the core's runtime role was created by the chain" \
     "$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
          "SELECT rolsuper||'/'||rolbypassrls||'/'||rolcanlogin FROM pg_roles WHERE rolname='kumbuka'" \
        </dev/null 2>&1)" "false/false/true"
  is "and ITS grant landed, by name, where V24's did not" \
     "$(execute_acl_in kumbuka_stagef stage_f_migrator kumbuka)" "t"
  is "issued under the function's owner, which is what makes it land" \
     "$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
          "SELECT pg_get_userbyid(a.grantor) FROM pg_proc p
             JOIN pg_namespace n ON n.oid=p.pronamespace
             CROSS JOIN LATERAL aclexplode(p.proacl) a
            WHERE n.nspname='platform' AND p.proname='tenant_id_by_alias'
              AND a.grantee='kumbuka'::regrole AND a.privilege_type='EXECUTE'" \
        </dev/null 2>&1)" "kumbuka_alias_resolver"
  is "and the migrator's own session came back out of that role" \
     "$(docker exec -i "$CT" psql -U stage_f_migrator -d kumbuka_stagef -qtAc \
          "SELECT current_user" </dev/null 2>&1)" "stage_f_migrator"
}

# ===========================================================================
case_chain() {
  hdr "A6 — V1..V23 unchanged, and the view's first four columns hold their place"

  say "the shape at V23"
  reset_cluster
  flyway --locations="filesystem:$(chain_dir_through 23)" --action=migrate | tail -1
  owner_sweep
  local before; before="$(view_shape)"
  printf '     %s\n' "$before"
  is "at V23 the view has exactly the four published columns" "$before" \
     "$V23_VIEW_SHAPE"

  say "V24 on top of it"
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  local after; after="$(view_shape)"
  printf '     %s\n' "$after"
  is "the four hold their name, type and position, and the three are appended" \
     "$after" "$V24_VIEW_SHAPE"
  is "the three new columns are the ones V24 names" \
     "$(sql "SELECT string_agg(column_name,',' ORDER BY ordinal_position)
               FROM information_schema.columns
              WHERE table_schema='platform' AND table_name='scope_access'
                AND ordinal_position > 4")" "kind,locked,can_write"

  say "V1..V23 are byte-identical to what the chain applied before V24"
  is "no checksum of an applied migration changed (flyway validate passes)" \
     "$(flyway --locations="filesystem:$MIGRATIONS" --action=migrate | grep -c 'migrate OK')" "1"

  say "V3's tenant isolation policy on platform.team is untouched"
  is "team_tenant_isolation still reads on app.tenant_id, for every role" \
     "$(sql "SELECT coalesce(roles::text,'')||'|'||coalesce(qual,'') FROM pg_policies
               WHERE schemaname='platform' AND tablename='team' AND policyname='team_tenant_isolation'" \
        | sed 's/ //g')" \
     "{public}|(tenant_id=(NULLIF(current_setting('app.tenant_id'::text,true),''::text))::uuid)"
  is "the new policy names only the resolver" \
     "$(sql "SELECT roles::text FROM pg_policies
               WHERE schemaname='platform' AND tablename='team' AND policyname='team_alias_resolution'")" \
     "{kumbuka_alias_resolver}"
}

# ===========================================================================
# RED PROBES. Each removes one thing from a database that is already at V24 and
# measures that the acceptance it belongs to goes red. A gate never seen failing
# is not a gate.
# ===========================================================================
case_red_memory_grant() {
  hdr "R1 — remove the EXECUTE grant to kumbuka_memory: A2 goes red for that role"
  prepare
  is "green first: kumbuka_memory resolves the alias" \
     "$(as kumbuka_memory "SELECT platform.tenant_id_by_alias('alpha')")" "$TENANT_A"
  sql "REVOKE EXECUTE ON FUNCTION platform.tenant_id_by_alias(text) FROM kumbuka_memory" >/dev/null
  local out; out="$(as kumbuka_memory "SELECT platform.tenant_id_by_alias('alpha')")"
  case "$out" in
    *"permission denied"*) ok "RED: kumbuka_memory is refused once the grant is gone";;
    *) bad "RED: kumbuka_memory is refused once the grant is gone" "$out";;
  esac
  say "and the other three are unaffected — the probe is specific, not a blanket"
  is "kumbuka_worklist still resolves" \
     "$(as kumbuka_worklist "SELECT platform.tenant_id_by_alias('alpha')")" "$TENANT_A"
}

case_red_author() {
  hdr "R2 — remove the private-scope author check: A3 goes red"
  prepare
  sql "UPDATE platform.scope SET created_by='$ALICE' WHERE tenant_id='$TENANT_A' AND kind='private'" >/dev/null
  is "green first: bob does not see alice's private scope" \
     "$(bound kumbuka_worklist "$TENANT_A" "$BOB" \
          "SELECT count(*) FROM platform.scope_access WHERE kind='private'")" "0"

  say "the view without the author clause — everything else identical"
  sql "CREATE OR REPLACE VIEW platform.scope_access AS
         SELECT s.id AS scope_id, s.tenant_id AS tenant_id, s.slug AS slug,
                s.archived AS archived, s.kind AS kind, s.locked AS locked,
                (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write
           FROM platform.scope s
           JOIN platform.user_account ua ON ua.tenant_id = s.tenant_id
          WHERE s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
            AND ua.subject  = NULLIF(current_setting('app.subject',   true), '')
            AND ua.status   = 'active'" >/dev/null
  local n; n="$(bound kumbuka_worklist "$TENANT_A" "$BOB" \
                  "SELECT count(*) FROM platform.scope_access WHERE kind='private'")"
  [[ "$n" == "1" ]] \
    && ok "RED: without the clause bob sees a private scope that is not his (count=$n)" \
    || bad "RED: without the clause bob sees another member's private scope" "count=$n"
}

# R3 took THREE attempts to build, and the two failures are the finding.
#
# The tenant boundary is carried by three things, not one, and a removal that
# leaves any of the other two standing measures nothing:
#
#   1. the view's own `s.tenant_id = app.tenant_id` clause;
#   2. V3's `scope_tenant_isolation` policy, which binds the view's owner under
#      FORCE ROW LEVEL SECURITY and filters the base table whether or not the
#      view says anything;
#   3. the membership join itself — `ua.tenant_id = s.tenant_id` with
#      `ua.subject = app.subject`. A subject that is a member of ONE tenant
#      cannot reach another tenant's scope through it even with clauses 1 and 2
#      both gone, because there is no user_account row to join against.
#
# So this case lifts them one at a time, and stages the subject that clause 1 is
# actually for: one that is an active member of BOTH tenants — which is a real
# shape, not a contrivance. The same Keycloak `sub` belongs to as many tenants
# as invited it, and for that subject the bound tenant is the ONLY thing that
# separates the two.
case_red_tenant() {
  hdr "R3 — remove the tenant predicate: A3 goes red for a subject in two tenants"
  prepare
  local MULTI=multi-tenant-sub
  sql "INSERT INTO platform.user_account (tenant_id, subject, email, role, status, muted) VALUES
         ('$TENANT_A','$MULTI','multi@alpha.test','member','active',false),
         ('$TENANT_B','$MULTI','multi@beta.test','member','active',false)" >/dev/null

  is "green first: no foreign-tenant scope is visible to a single-tenant member" \
     "$(bound kumbuka_worklist "$TENANT_A" "$BOB" \
          "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")" "0"
  is "green first: nor to the member of BOTH tenants, bound to A" \
     "$(bound kumbuka_worklist "$TENANT_A" "$MULTI" \
          "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")" "0"

  say "the view without the tenant clause — everything else identical"
  local without_tenant="CREATE OR REPLACE VIEW platform.scope_access AS
         SELECT s.id AS scope_id, s.tenant_id AS tenant_id, s.slug AS slug,
                s.archived AS archived, s.kind AS kind, s.locked AS locked,
                (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write
           FROM platform.scope s
           JOIN platform.user_account ua ON ua.tenant_id = s.tenant_id
          WHERE ua.subject = NULLIF(current_setting('app.subject', true), '')
            AND ua.status  = 'active'
            AND (s.kind <> 'private' OR s.created_by IS NULL
                 OR s.created_by = NULLIF(current_setting('app.subject', true), ''))"
  sql "$without_tenant" >/dev/null
  is "layer 2 holds: V3's policy still filters the base table under FORCE RLS" \
     "$(bound kumbuka_worklist "$TENANT_A" "$MULTI" \
          "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")" "0"

  say "now release the policy's grip on the view owner — the exemption a"
  say "BYPASSRLS owner, or a superuser-owned view, would have for free"
  sql "ALTER TABLE platform.scope        NO FORCE ROW LEVEL SECURITY" >/dev/null
  sql "ALTER TABLE platform.user_account NO FORCE ROW LEVEL SECURITY" >/dev/null
  is "layer 3 still holds for a single-tenant member: no row to join against" \
     "$(bound kumbuka_worklist "$TENANT_A" "$BOB" \
          "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")" "0"

  local n; n="$(bound kumbuka_worklist "$TENANT_A" "$MULTI" \
                  "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")"
  [[ "$n" -gt 0 ]] \
    && ok "RED: for the member of both tenants, the other tenant's scope appears (count=$n)" \
    || bad "RED: for the member of both tenants, the other tenant's scope appears" "count=$n"

  say "and the control: put the clause back, leave the owner exempt"
  sql "CREATE OR REPLACE VIEW platform.scope_access AS
         SELECT s.id AS scope_id, s.tenant_id AS tenant_id, s.slug AS slug,
                s.archived AS archived, s.kind AS kind, s.locked AS locked,
                (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write
           FROM platform.scope s
           JOIN platform.user_account ua ON ua.tenant_id = s.tenant_id
          WHERE s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
            AND ua.subject  = NULLIF(current_setting('app.subject',   true), '')
            AND ua.status   = 'active'
            AND (s.kind <> 'private' OR s.created_by IS NULL
                 OR s.created_by = NULLIF(current_setting('app.subject', true), ''))" >/dev/null
  is "the view's own clause is load-bearing on its own: zero foreign rows again" \
     "$(bound kumbuka_worklist "$TENANT_A" "$MULTI" \
          "SELECT count(*) FROM platform.scope_access WHERE tenant_id <> '$TENANT_A'")" "0"
  is "and it still answers for the bound tenant — it filters, it does not empty" \
     "$(bound kumbuka_worklist "$TENANT_A" "$MULTI" \
          "SELECT count(*) FROM platform.scope_access")" "5"
}

case_red_superuser() {
  hdr "R4 — why every probe above connects as the service role"
  prepare
  say "remove the SELECT on the view from kumbuka_dispatch"
  sql "REVOKE SELECT ON platform.scope_access FROM kumbuka_dispatch" >/dev/null

  say "the same question, asked as the SUPERUSER migrator:"
  local su; su="$(sql "BEGIN;
                       SELECT set_config('app.tenant_id','$TENANT_A',true);
                       SELECT set_config('app.subject','$BOB',true);
                       SELECT count(*) FROM platform.scope_access;
                       COMMIT;" | tail -1)"
  [[ "$su" -gt 0 ]] \
    && ok "as superuser the probe stays GREEN with the grant removed (count=$su) — it measures nothing" \
    || bad "as superuser the probe stays green with the grant removed" "got [$su]"

  say "the same question, asked as the service role:"
  local svc; svc="$(bound kumbuka_dispatch "$TENANT_A" "$BOB" "SELECT count(*) FROM platform.scope_access")"
  case "$svc" in
    *"permission denied"*) ok "RED: as kumbuka_dispatch the very same query is refused";;
    *) bad "RED: as kumbuka_dispatch the very same query is refused" "$svc";;
  esac

  say "back to the granted state, and the same removed grant is red again"
  sql "GRANT SELECT ON platform.scope_access TO kumbuka_dispatch" >/dev/null
  is "restored: the service role reads the contract again" \
     "$(bound kumbuka_dispatch "$TENANT_A" "$BOB" "SELECT count(*) FROM platform.scope_access")" "5"
}

case_red_resolver() {
  hdr "RED — remove the resolver's policy on platform.team: the alias lookup goes silent"
  prepare
  is "green first: the alias resolves" \
     "$(as kumbuka_worklist "SELECT platform.tenant_id_by_alias('alpha')")" "$TENANT_A"
  sql "DROP POLICY team_alias_resolution ON platform.team" >/dev/null
  local out; out="$(as kumbuka_worklist "SELECT coalesce(platform.tenant_id_by_alias('alpha')::text,'<NULL>')")"
  is "RED: without the policy a KNOWN alias answers NULL — indistinguishable from an unknown one" \
     "$out" "<NULL>"
  say "that silence is the failure mode the resolver role exists to prevent."
}

# ===========================================================================
# V25 — the core's runtime role is `kumbuka`, and it holds the lookup because
# the chain says so.
#
# THE SUBSTRATE OF THIS CASE DELIBERATELY OMITS `owner_sweep`. Every other case
# here calls it through `prepare`, and the sweep issues USAGE and EXECUTE to
# the runtime role itself — the two grants a correct deployment has to carry
# along with the ownership it moves. A case that ran after it would be green
# whether or not V25 existed, because the helper would have done V25's work.
#
# What is left standing instead is the exact shape the defect was measured in: the
# chain applied by a superuser, `platform.scope` owned by that superuser, and
# `kumbuka` a plain login role that owns nothing. V24's EXECUTE grant goes to
# the owner of the inventory and therefore misses it; V25's goes to `kumbuka`
# by name and does not.
# Does the lookup's access list carry an EXECUTE entry naming <role>?
#
#   execute_acl_in <database> <as user> <role>
#
# Asked of the ACL and never of has_function_privilege, which answers true for
# every role while PUBLIC still holds EXECUTE and therefore cannot witness a
# grant to a PARTICULAR one. That is not hypothetical: under the stage-F
# migrator V24's own `REVOKE ALL … FROM PUBLIC` is a silent no-op, and
# `case_migrator` below measures what has_function_privilege reports there.
execute_acl_in() {
  docker exec -i "$CT" psql -U "$2" -d "$1" -qtAc \
    "SELECT EXISTS (
       SELECT 1 FROM pg_catalog.pg_proc p
         JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
         CROSS JOIN LATERAL aclexplode(p.proacl) a
        WHERE n.nspname='platform' AND p.proname='tenant_id_by_alias'
          AND p.pronargs = 1
          AND a.grantee = '$3'::regrole
          AND a.privilege_type = 'EXECUTE')" </dev/null 2>&1
}

core_holds_execute() { execute_acl_in "$DB" "$MIGRATOR" kumbuka; }

case_coregrant() {
  hdr "K1 — the core's runtime role holds the alias lookup BY NAME, with no deployment step"
  reset_cluster
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  seed_population

  say "the shape the defect was measured in: the migrator owns the inventory"
  is "platform.scope is owned by the migrator, not by the runtime role" \
     "$(sql "SELECT pg_get_userbyid(c.relowner) FROM pg_class c
               JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname='platform' AND c.relname='scope'")" "postgres"

  # The expectation here is this sprint's sentence — `kumbuka` holds EXECUTE —
  # and NOT "whoever owns platform.scope holds EXECUTE", which is the catalogue
  # indirection V25 exists to remove. Read out of the ACL rather than out of
  # has_function_privilege: that function answers true for any role while
  # PUBLIC still holds EXECUTE, so it cannot witness a grant to THIS one.
  is "the function's access list names kumbuka" "$(core_holds_execute)" "t"
  is "and kumbuka may use the schema the function lives in" \
     "$(sql "SELECT has_schema_privilege('kumbuka','platform','USAGE')")" "t"

  say "and the lookup answers, as the role the deployment connects as"
  is "kumbuka resolves the known alias 'alpha'" \
     "$(as kumbuka "SELECT platform.tenant_id_by_alias('alpha')")" "$TENANT_A"
  is "kumbuka resolves the known alias 'beta'" \
     "$(as kumbuka "SELECT platform.tenant_id_by_alias('beta')")" "$TENANT_B"
  is "kumbuka gets NULL for an unknown alias" \
     "$(as kumbuka "SELECT coalesce(platform.tenant_id_by_alias('nosuch')::text,'<NULL>')")" "<NULL>"
  is "and the same answer with a tenant and a subject bound, as the core binds them" \
     "$(bound kumbuka "$TENANT_A" "$ALICE" "SELECT platform.tenant_id_by_alias('alpha')")" "$TENANT_A"

  say "a cluster that has no kumbuka yet: V25 creates it, owning nothing"
  reset_cluster
  sql "DROP ROLE kumbuka" >/dev/null
  is "the role really is absent before the chain runs" \
     "$(sql "SELECT count(*) FROM pg_roles WHERE rolname='kumbuka'")" "0"
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  is "afterwards it exists, and it is neither a superuser nor BYPASSRLS (super/bypassrls/login)" \
     "$(sql "SELECT rolsuper||'/'||rolbypassrls||'/'||rolcanlogin FROM pg_roles WHERE rolname='kumbuka'")" \
     "false/false/true"
  is "and it holds the lookup" "$(core_holds_execute)" "t"

  # The half that a create-if-absent block gets wrong by being one line too
  # eager: an installation already connecting as `kumbuka` must keep
  # authenticating with the secret it has. V25 touches no attribute of an
  # existing role — no password, no rename, no ALTER at all.
  say "a cluster that already has kumbuka, with a password the deployment knows"
  reset_cluster
  sql "ALTER ROLE kumbuka PASSWORD 'known-secret-of-this-installation'" >/dev/null
  require_password_auth
  is "green first: the role authenticates with the installation's secret" \
     "$(can_authenticate kumbuka known-secret-of-this-installation)" "yes"
  flyway --locations="filesystem:$MIGRATIONS" --action=migrate | tail -1
  is "it still does after V25" \
     "$(can_authenticate kumbuka known-secret-of-this-installation)" "yes"
  is "and V25's placeholder password was NOT written over it" \
     "$(can_authenticate kumbuka change-me-kumbuka)" "no"
  is "and the grant landed all the same" "$(core_holds_execute)" "t"
}

# ===========================================================================
case_red_core_grant() {
  hdr "RED — cut V25's grant block out: the core is refused the lookup it needs"

  local nogrant; nogrant="$(chain_dir_without_block core-execute 'V25__*.sql')"
  is "the copy lost both markers" \
     "$(grep -c 'core-execute' "$nogrant"/V25__*.sql)" "0"
  is "and the grant statement went with them" \
     "$(grep -c '^ *GRANT EXECUTE ON FUNCTION' "$nogrant"/V25__*.sql)" "0"

  reset_cluster
  flyway --locations="filesystem:$nogrant" --action=migrate | tail -1
  seed_population

  is "RED: the function's access list no longer names kumbuka" "$(core_holds_execute)" "f"
  is "RED: and the role V25 created still cannot execute it" \
     "$(sql "SELECT has_function_privilege('kumbuka','platform.tenant_id_by_alias(text)','EXECUTE')")" "f"
  local out; out="$(as kumbuka "SELECT platform.tenant_id_by_alias('alpha')")"
  case "$out" in
    *"permission denied for function"*) ok "RED: the call is refused (42501)";;
    *) bad "RED: the call is refused" "$out";;
  esac
  say "that refusal is the defect itself: the resolver answers, and the core may not ask."
  say "the section-2 grant is untouched by the cut, so the schema is still reachable —"
  is "which is how this probe shows the EXECUTE grant alone is what went missing" \
     "$(sql "SELECT has_schema_privilege('kumbuka','platform','USAGE')")" "t"
}

# ===========================================================================
# The other half of V25's own safety, and the reason it has one.
#
# A GRANT issued by a role that does not own the object does not raise. It
# WARNS and changes nothing (measured 2026-09-22 under a CREATEROLE migrator:
# `WARNING: no privileges were granted for "tenant_id_by_alias"`), and the
# migration reports success. A chain that reported success while its grant went
# nowhere is exactly how this defect reached a release, so V25 reads the ACL back and
# refuses to finish when the grant is not there.
#
# Cutting the grant branch alone leaves that read-back standing, which is what
# this probe is: not the state of a database, but whether the migration stops.
case_red_core_verify() {
  hdr "RED — cut V25's grant but keep its read-back: the migration must refuse to finish"

  local noissue; noissue="$(chain_dir_without_block core-grant 'V25__*.sql')"
  is "the copy lost the grant branch" \
     "$(grep -c '^ *GRANT EXECUTE ON FUNCTION' "$noissue"/V25__*.sql)" "0"
  is "and kept the read-back that follows it" \
     "$(grep -c 'aclexplode' "$noissue"/V25__*.sql)" "1"

  reset_cluster
  local out; out="$(flyway --locations="filesystem:$noissue" --action=migrate)"
  case "$out" in
    *"did not land"*) ok "RED: V25 stops the chain and says the grant did not land";;
    *) bad "RED: V25 stops the chain" "$(printf '%s' "$out" | tr '\n' ' ')";;
  esac
  is "RED: and the database is left without the grant, not with a false one" \
     "$(core_holds_execute)" "f"
  say "a warning in a log is not a gate. the read-back is."
}

# ===========================================================================
resolve_classpath
compile_driver

ALL=(resolution visibility writeright rolename md5guard migrator coregrant chain
     red-memory-grant red-author red-tenant red-superuser red-resolver red-md5guard
     red-core-grant red-core-verify)
SELECTED=("${@:-}")
[[ -z "${SELECTED[0]:-}" ]] && SELECTED=("${ALL[@]}")

for c in "${SELECTED[@]}"; do
  case "$c" in
    resolution)       case_resolution;;
    visibility)       case_visibility;;
    writeright)       case_writeright;;
    rolename)         case_rolename;;
    md5guard)         case_md5guard;;
    migrator)         case_migrator;;
    coregrant)        case_coregrant;;
    chain)            case_chain;;
    red-memory-grant) case_red_memory_grant;;
    red-author)       case_red_author;;
    red-tenant)       case_red_tenant;;
    red-superuser)    case_red_superuser;;
    red-resolver)     case_red_resolver;;
    red-md5guard)     case_red_md5guard;;
    red-core-grant)   case_red_core_grant;;
    red-core-verify)  case_red_core_verify;;
    *) die "unknown case: $c";;
  esac
done

printf '\n======================================================================\n'
printf 'PASS %d   FAIL %d\n' "$PASS" "$FAIL"
printf '======================================================================\n'
[[ "$FAIL" -eq 0 ]] || exit 1
