#!/usr/bin/env bash
# ===========================================================================
# measure.sh — step 0 of sprint/186.6: what the contract is TODAY, before a
# line of V24 is written. Every answer here is read out of a real database at
# V23, never out of a file and never out of a memory of one.
# ===========================================================================
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
die() { printf 'MEASURE ABORTED: %s\n' "$*" >&2; exit 1; }
# shellcheck source=substrate.sh
source "$HERE/substrate.sh"

hdr() { printf '\n======================================================================\n%s\n======================================================================\n' "$*"; }
say() { printf -- '--- %s\n' "$*"; }

resolve_classpath
compile_driver
reset_cluster
say "chain V1..V23 — this script measures the state BEFORE V24"
flyway --locations="filesystem:$(chain_dir_through 23)" --action=migrate | tail -1
owner_sweep
seed_population

hdr "M1a — the definition of platform.scope_access at V23"
sql "SELECT pg_get_viewdef('platform.scope_access'::regclass, true)"

hdr "M1b — its owner, and the owner's attributes"
sql "SELECT c.relname, pg_get_userbyid(c.relowner) AS owner, r.rolsuper, r.rolbypassrls
       FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
       JOIN pg_roles r ON r.oid=c.relowner
      WHERE n.nspname='platform' AND c.relname='scope_access'"

hdr "M1c — every grant on the view and on the schema"
say "view"
sql "SELECT grantee, privilege_type FROM information_schema.role_table_grants
      WHERE table_schema='platform' AND table_name='scope_access' ORDER BY grantee, privilege_type"
say "schema platform"
sql "SELECT r.rolname, has_schema_privilege(r.rolname,'platform','USAGE') AS usage
       FROM pg_roles r WHERE r.rolname LIKE 'kumbuka%' ORDER BY r.rolname"

hdr "M1d — the roles of V6, V21, V22 with their attributes and their ownership"
sql "SELECT rolname, rolsuper, rolbypassrls, rolcanlogin, rolcreaterole
       FROM pg_roles WHERE rolname LIKE 'kumbuka%' ORDER BY rolname"
say "what each kumbuka% role owns in platform/public"
sql "SELECT pg_get_userbyid(c.relowner) AS owner, count(*)
       FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=ANY(ARRAY['platform','public']) AND c.relkind=ANY(ARRAY['r','v']::\"char\"[])
      GROUP BY 1 ORDER BY 1"

hdr "M1e — the state after V23: where the inventory lives"
sql "SELECT n.nspname, c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=ANY(ARRAY['platform','public']) AND c.relkind=ANY(ARRAY['r','v']::\"char\"[])
      ORDER BY 1,2"
say "flyway history head"
sql "SELECT max(version::numeric) FROM flyway_schema_history WHERE success"

hdr "M1f — what the view answers today, AS A SERVICE ROLE, bound to tenant A as alice"
say "kumbuka_worklist:"
bound kumbuka_worklist "$TENANT_A" "$ALICE" "SELECT * FROM platform.scope_access ORDER BY slug"
say "kumbuka_logbook:"
bound kumbuka_logbook "$TENANT_A" "$ALICE" "SELECT * FROM platform.scope_access ORDER BY slug"
say "kumbuka_memory:"
bound kumbuka_memory "$TENANT_A" "$ALICE" "SELECT * FROM platform.scope_access ORDER BY slug"

hdr "M2 — does team_tenant_id_by_alias still resolve after V23?"
say "installing the ops-console bootstrap definition verbatim (08-tenant-routing-fn.sql)"
sqlq "ALTER ROLE kumbuka_ops_reader RENAME TO kumbuka_operator" >/dev/null
docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -U "$MIGRATOR" -d "$DB" -q \
  < /Users/johannes/Work/kumbuka.ai/dev/ops-console/deploy/bootstrap/08-tenant-routing-fn.sql
say "its pinned search_path, its owner, that owner's bypassrls:"
sql "SELECT p.proname, p.prosecdef, p.proconfig, pg_get_userbyid(p.proowner) AS owner,
            (SELECT rolbypassrls FROM pg_roles WHERE oid=p.proowner) AS owner_bypassrls
       FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE p.proname='team_tenant_id_by_alias'"
say "AS the runtime role kumbuka (which holds EXECUTE), known alias 'alpha':"
as kumbuka "SELECT team_tenant_id_by_alias('alpha')"
say "^ the finding: the function's OWNER holds SELECT on platform.team but no"
say "  USAGE on the schema, so the name resolves to nothing. V21 grants that"
say "  role nothing in platform — the schema's grants are enumerated per role,"
say "  and this owner is not among them — and V23 grants USAGE only to the"
say "  owner of the moved tables. The repair exists in the bootstrap as"
say "  14-platform-search-path.sql; this measures what happens without it."
sql "SELECT has_schema_privilege('kumbuka_operator','platform','USAGE') AS schema_usage,
            has_table_privilege('kumbuka_operator','platform.team','SELECT') AS table_select"
say "with the USAGE grant the bootstrap issues:"
sql "GRANT USAGE ON SCHEMA platform TO kumbuka_operator" >/dev/null
as kumbuka "SELECT team_tenant_id_by_alias('alpha')"
say "AS kumbuka, unknown alias 'nosuch':"
as kumbuka "SELECT coalesce(team_tenant_id_by_alias('nosuch')::text,'<NULL>')"
say "AS kumbuka_worklist (a service role — holds NO execute):"
as kumbuka_worklist "SELECT team_tenant_id_by_alias('alpha')"

hdr "M2b — WHY it resolves: is the owner's RLS exemption load-bearing?"
say "team is FORCE RLS:"
sql "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class
      WHERE oid='platform.team'::regclass"
say "counter-measurement: the same body, owned by the NON-bypassrls runtime role"
sql "CREATE OR REPLACE FUNCTION probe_alias_nonbypass(p_alias text) RETURNS uuid
       LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, platform
       AS \$\$ SELECT tenant_id FROM team WHERE alias = p_alias \$\$;
     ALTER FUNCTION probe_alias_nonbypass(text) OWNER TO $RUNTIME;
     GRANT EXECUTE ON FUNCTION probe_alias_nonbypass(text) TO kumbuka_worklist;" >/dev/null
say "AS kumbuka_worklist, no app.tenant_id bound (the real situation at alias time):"
as kumbuka_worklist "SELECT coalesce(probe_alias_nonbypass('alpha')::text,'<NULL>')"

hdr "M3 — the write logic in the core (MemberWritePolicy) as a specification"
sed -n '1,40p' "$SERVER_ROOT/backend/server/src/main/java/ai/kumbuka/service/MemberWritePolicy.java"
say "the two asserts, verbatim:"
sed -n '/public void assertCanWriteShared/,/^    }/p'  "$SERVER_ROOT/backend/server/src/main/java/ai/kumbuka/service/MemberWritePolicy.java"
sed -n '/public void assertScopeWritable/,/^    }/p'   "$SERVER_ROOT/backend/server/src/main/java/ai/kumbuka/service/MemberWritePolicy.java"
say "every caller of the two asserts, with the channel and admin flag it passes:"
grep -rn "assertCanWriteShared\|assertScopeWritable" "$SERVER_ROOT/backend/server/src/main/java/" || true

hdr "M4 — how a private scope knows its author, and who may see a global one"
say "columns of platform.scope:"
sql "SELECT column_name, data_type, is_nullable FROM information_schema.columns
      WHERE table_schema='platform' AND table_name='scope' ORDER BY ordinal_position"
say "the uniqueness the chain puts on private and global scopes:"
sql "SELECT indexname, indexdef FROM pg_indexes
      WHERE schemaname='platform' AND tablename='scope' ORDER BY indexname"
say "how many private scopes a tenant can hold, measured — a second insert:"
sqlq "INSERT INTO platform.scope (tenant_id, name, slug, kind, archived, locked, fixed)
        VALUES ('$TENANT_A','private-two','private-two','private',false,false,false)"
say "created_by on the seeded private and global scopes:"
sql "SELECT slug, kind, coalesce(created_by,'<NULL>') FROM platform.scope
      WHERE tenant_id='$TENANT_A' AND kind <> 'project' ORDER BY slug"
say "where privacy actually sits — the memory table's owner column:"
sql "SELECT column_name FROM information_schema.columns
      WHERE table_schema='public' AND table_name='memory' AND column_name IN ('owner_subject','scope_id')"
say "and the core's rule for it:"
grep -n "private row is invisible\|PRIVATE && !m.ownerSubject" "$SERVER_ROOT/backend/server/src/main/java/ai/kumbuka/repo/MemoryRepository.java" || true

hdr "M5 — the password verb of kumbuka_logbook (a RENAME drops an MD5 password)"
sql "SELECT rolname,
            CASE WHEN rolpassword IS NULL THEN '<none>'
                 WHEN rolpassword LIKE 'SCRAM-SHA-256%' THEN 'SCRAM-SHA-256'
                 WHEN rolpassword LIKE 'md5%' THEN 'MD5'
                 ELSE 'other' END AS verb
       FROM pg_authid WHERE rolname LIKE 'kumbuka%' ORDER BY rolname"
say "the cluster's password_encryption default:"
sql "SHOW password_encryption"
say "and what a RENAME does to it, measured:"
sql "ALTER ROLE kumbuka_logbook RENAME TO kumbuka_dispatch_probe" >/dev/null
sql "SELECT rolname, CASE WHEN rolpassword IS NULL THEN '<none>'
                          WHEN rolpassword LIKE 'SCRAM-SHA-256%' THEN 'SCRAM-SHA-256'
                          WHEN rolpassword LIKE 'md5%' THEN 'MD5' ELSE 'other' END
       FROM pg_authid WHERE rolname='kumbuka_dispatch_probe'"

printf '\nMEASURE DONE (container %s left up for follow-up; docker rm -f %s to drop it)\n' "$CT" "$CT"
