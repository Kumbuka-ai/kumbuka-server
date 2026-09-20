# The platform read contract — what `V24` publishes, and how it is witnessed

`platform.scope_access` and `platform.tenant_id_by_alias` are the two questions
every service behind this platform asks of the core's database, and the only two
it may ask. This directory holds the probes that witness them, and the
measurements that decided their shape.

Nothing here runs in a deployment. The contract itself is
`backend/server/src/main/resources/db/migration/V24__platform_read_contract.sql`;
what lives here drives that file against throwaway Postgres containers.

## The two questions

**`platform.tenant_id_by_alias(text) -> uuid`** — an alias to a tenant id, and
nothing else: no name, no count, no way to tell a disabled tenant from an absent
one. A service needs it BEFORE it has a tenant to bind, because D-OPS-26 rules
the tenant id out of the token and leaves only the alias.

**`platform.scope_access`** — one row per scope the calling subject may enter,
under two transaction-local settings (`app.tenant_id`, `app.subject`), both
fail-closed. Seven columns since V24: the four V21 published (`scope_id`,
`tenant_id`, `slug`, `archived`) plus `kind`, `locked` and `can_write`.

## Running the probes

```sh
./test/read-contract-probe.sh              # every case, ~14 containers
./test/read-contract-probe.sh resolution   # one case
KEEP=1 ./test/read-contract-probe.sh chain # leave the container up
./test/measure.sh                          # step 0: the state at V23
```

`measure.sh` installs one file from the SIBLING ops-console repository
(`ops-console/deploy/bootstrap/08-tenant-routing-fn.sql`). It finds it by
walking up from this checkout to the workspace that holds both, which a git
worktree under `.claude/worktrees/` does not sit inside — so set
`TENANT_ROUTING_FN` to the file's path when the walk cannot reach it. Either
way the script stops and says so rather than measuring a database that never
received the definition.

Docker, `mvn` and `javac` must be on PATH — the suite resolves flyway-core in
exactly the version `backend/server/pom.xml` resolves, so it drives the same
migrator a container does rather than a stand-in.

## Why every claim is made as a service role

A superuser is exempt from row-level security and from every privilege check. A
probe that queries as the migrator therefore returns rows whether or not the
grant it claims to witness exists — it is not a weaker test, it is a test of
something else.

Case `red-superuser` measures this rather than asserting it: it removes a grant,
asks the question as the superuser and watches the probe stay GREEN, then asks
the identical question as `kumbuka_dispatch` and watches it go red.

The same blindness bit this suite while it was being written. Every case
migrated as `postgres`, and `ALTER FUNCTION … OWNER TO kumbuka_alias_resolver`
was quietly failing for the migrator stage F intends — a superuser is exempt
from the rule that a new owner must hold CREATE on its object's schema. It was
`MigrationCallbackWitnessIT` that turned red. Case `migrator` now brings that
shape here.

## The cases

| case | what it witnesses |
|---|---|
| `resolution` | the alias lookup answers for all four roles, refuses a fifth, and widens nothing else |
| `visibility` | kind, lock, membership and the tenant boundary, as each of the three service roles |
| `writeright` | `can_write` against `MemberWritePolicy`'s decision, ten combinations |
| `rolename` | `kumbuka_logbook` becomes `kumbuka_dispatch` and keeps its grants and its password — and the collision case, where the dispatch service's own chain got there first |
| `md5guard` | the rename is refused where it would DELETE the password, and applies once it would not — and the migrator that is not allowed to look |
| `migrator` | the whole chain under a CREATEROLE non-superuser migrator |
| `chain` | V1..V23 unchanged; the view's seven columns match a form written out in the probe, not one read back out of the database |

## The red probes

A gate never seen failing is not a gate. Each of these removes one thing from a
database already at V24 and measures that the acceptance it belongs to goes red.

| probe | removal | expected |
|---|---|---|
| `red-memory-grant` | the EXECUTE grant to `kumbuka_memory` | that role alone is refused |
| `red-author` | the private-scope author clause | a peer sees a private scope that is not theirs |
| `red-tenant` | the view's tenant clause | see below |
| `red-superuser` | a SELECT grant, asked as superuser then as the service role | green, then red |
| `red-resolver` | the alias policy on `platform.team` | a KNOWN alias answers NULL |
| `red-md5guard` | the MD5 guard, cut out of V24 itself | the rename applies, reports success, and the role can no longer log in |

`red-md5guard` is the one probe that cannot be staged by editing the database
afterwards: what the guard prevents is the migration's own act, so the chain is
copied with the marked block cut out and applied from the copy.

## What the MD5 guard costs a migrator that is not a superuser

V24 reads the password verifier of `kumbuka_logbook` out of `pg_catalog.pg_authid`
before renaming it, because a rename DELETES an MD5 verifier — it is salted with
the role name — and leaves a service role that holds every privilege and cannot
authenticate, after a migration that reported success.

A CREATEROLE non-superuser may not read that catalogue, and cannot grant itself
the read either (a predefined role's ADMIN option belongs to the superuser).
`pg_roles` is no way around it: its `rolpassword` column is the constant
`********` for every role, set or unset. So such a migrator is refused the
rename rather than performing it blind, and the exception names the one grant
that lifts it:

```sql
GRANT SELECT ON pg_catalog.pg_authid TO <migrator>;   -- in the database being migrated
```

The `IN THE DATABASE` part is not decoration: a shared catalogue's ACL is stored
per database. Granted in one, `has_table_privilege` answers true there and false
in the next database of the same cluster.

Today's deployment is unaffected — it migrates the core as the superuser
(`QUARKUS_FLYWAY_USERNAME` in `infra/compose.prod.yml`). The stage-F shape needs
the grant, and `case_migrator` and `MigrationCallbackWitnessIT` now issue it.

`red-tenant` took three attempts, and the two failures are the finding. The
tenant boundary is carried by THREE things — the view's own clause, V3's
`scope_tenant_isolation` policy under FORCE ROW LEVEL SECURITY, and the
membership join itself — and a removal that leaves any of the other two standing
measures nothing. The case now lifts them one at a time, against a subject that
is an active member of BOTH tenants, which is the shape the view's own clause is
actually for.

## Findings this directory records and does not repair

Both are properties of V23 and the deployment path, and this sprint's Grenze
rules out changing either.

1. **A fresh installation's grants land on the migrator.** V23 grants USAGE on
   `platform` to whoever owns `platform.scope` AT MIGRATION TIME, and V24 reads
   the core's role the same way — it is the only way to name a role whose name
   differs by installation. On a database that was owner-normalised BEFORE the
   chain ran, that is the runtime role and both grants are correct. On a fresh
   one the migrator owns everything while the chain runs, the sweep re-owns the
   tables afterwards, and the runtime role is left holding tables it cannot
   reach and a function it may not execute. The owner-normalisation step has to
   carry both grants along with the ownership it moves;
   `10-owner-normalization.sql` issues no grant at all today. `case_migrator`
   asserts the gap; `owner_sweep()` in `substrate.sh` closes it the way a
   correct deployment would.

2. **`public.team_tenant_id_by_alias` stops resolving after V23 without one
   grant.** Its owner holds SELECT on `platform.team` and no USAGE on the
   schema, so the name resolves to nothing and every tenant-scoped request
   answers 401 — a failed lookup is indistinguishable from an unknown tenant by
   design. `14-platform-search-path.sql` in ops-console's bootstrap is the
   repair, and it is a bootstrap step rather than a migration, so whether a
   given cluster has it is a deployment question. `measure.sh` shows both
   states.
