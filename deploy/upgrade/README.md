# Upgrade steps

One-off steps that a database needs and a migration cannot perform. Each file
here is run by hand (or by a deployment's wrapper) at a point the release notes
name; none of them is applied automatically at container start.

## `stage-f-relocate-history.sql`

Moves `flyway_schema_history` from `public` into `platform` and, in the same
transaction, points the migrator and the runtime role at that schema.

### Who needs it, and when

| Situation | What to do |
|---|---|
| **Existing installation**, upgrading to the release that contains `V23` | Run it **before** starting the new image. |
| **Fresh installation** | Install and start normally, let the first start finish, then run it **once** — most easily via [`finish-fresh-install.sh`](#finish-fresh-installsh). It is **not optional**: a fresh installation starts exactly once until the history has moved. |
| Already ran it | Nothing. A second run reports that the history is in place and changes nothing. |

The application may keep serving while it runs. It touches the history table and
two role settings, and a running image reads neither until it next starts. Do
not run it in the middle of a deploy: it refuses while a Flyway run holds the
history, which is the correct answer, not a problem to work around.

### Running it

```
psql -U postgres -d kumbuka -v ON_ERROR_STOP=1 -f stage-f-relocate-history.sql
```

`db` defaults to `kumbuka` and must name the database you are CONNECTED to —
the history moves in the connected database while `ALTER ROLE ... IN DATABASE`
uses `db`, so a mismatch would split the two halves apart. Since sprint/186.5
the file refuses on a mismatch instead of reporting success; pass
`-v db=<name>` whenever your database is not called `kumbuka`.

## `finish-fresh-install.sh`

The one step that finishes a FRESH installation, as a single idempotent
command. It runs `stage-f-relocate-history.sql` with `migrator` and `runtime`
both set to the app role, which is the CE shape: one role migrates and runs.

```
./finish-fresh-install.sh
KUMBUKA_DB_NAME=kumbuka_prod ./finish-fresh-install.sh
PSQL="docker exec -i kumbuka-postgres psql" ./finish-fresh-install.sh
```

### Why a fresh installation needs it

`init-db.sh` pins the app role's search_path to `platform, public` at creation,
so `current_schema()` is `public` until V21 creates `platform` and `platform`
from then on. Flyway keeps its history in `current_schema()`. Measured
2026-09-20 against PostgreSQL 16 and Flyway 12.0.0:

```
boot 1  Schema history table "public"."flyway_schema_history" does not exist yet
        RESULT migrate OK executed=22
boot 2  Schema history table "platform"."flyway_schema_history" does not exist yet
        FlywayException: Found non-empty schema(s) "platform" but no schema
        history table.
```

With `baseline-on-migrate` off — deliberately, so a half state is loud — the
second boot is a hard refusal. Running this once after the first start moves
the history to where every later boot looks for it.

Three names can be overridden; the defaults are what a stock installation uses.

```
psql -U postgres -d kumbuka -v ON_ERROR_STOP=1 \
     -v migrator=postgres -v runtime=kumbuka -v db=kumbuka \
     -f stage-f-relocate-history.sql
```

* `migrator` — the role Flyway connects as (`QUARKUS_FLYWAY_USERNAME`). Its
  search_path is set `IN DATABASE`, because this role is usually the cluster
  superuser and the cluster carries other databases.
* `runtime` — the role the application connects as
  (`QUARKUS_DATASOURCE_USERNAME`). Its search_path is set globally; the role
  exists for this application alone.
* `db` — the database the migrator's setting is scoped to.

Deployments that use the `infra` repository have a wrapper that fills these in
from the host environment and runs the file inside the Postgres container:
`scripts/stage-f-relocate-history.sh`.


### Why the relocation exists

Since `V23` the tenancy inventory lives in the schema `platform`. Flyway,
configured with neither `schemas` nor `defaultSchema`, keeps its history in
`current_schema()` — the first entry of the migrator's search_path — so the
place the history lives and the path the migrator uses are one fact. Changing
either alone leaves the next start either inventing a second history or failing
to find the first.

A migration cannot make this change itself. Flyway holds a lock on
`flyway_schema_history` for the whole of a migrate run, so a migration that
tried to move it would wait on Flyway; with the shipped `lock_timeout = 0` it
would wait forever, and since migrations run at container start, the container
would never finish booting and never fail either.

### If it refuses

Every refusal leaves the database unchanged — the file is one transaction.

| Message says | Meaning |
|---|---|
| history in **both** schemas | An earlier attempt stopped half-way. Decide by hand which history is the real one before re-running. |
| **no** history in either | The chain has never run here. Install normally first. |
| could not **lock** the history | A Flyway run is in progress. Stop the application container and run it again. |
| schema `platform` does not exist | The installation has not reached `V21`. Upgrade to that release first. |

## The order of the rollout, and the one window in it

| Step | What happens |
|---|---|
| 1 | The installation is running the previous image. Nothing to do. |
| 2 | Run `stage-f-relocate-history.sql`. The application keeps serving. |
| 3 | Deploy the image that carries `V23`. |

**Steps 2 and 3 go one after the other, with no restart of the backend in
between.** Between them the database has already moved and the previous image
is still what a restart would bring up — and it does not survive that restart.
Hibernate resolves an unqualified entity against the connection's *one* default
schema, and step 2 has already pointed that schema at `platform` while the
tables are still in `public`; it does not walk the rest of the search_path.
Measured in sprint/185.2.

**If step 3 fails before `V23` is applied, the way back is
`stage-f-relocate-history-revert.sql`.** It puts the database back in the state
step 2 found it in, and the previous image starts again.

**Once `V23` has been applied there is no way back, and the revert says so.**
That is not a property of this step: Flyway refuses to migrate against a
database carrying an applied version it cannot resolve locally, which holds for
any release that adds a migration at all.

## `stage-f-relocate-history-revert.sql`

The way back from the relocation, for the window between step 2 and step 3.

It moves `flyway_schema_history` from `platform` back to `public`, resets the
two search_path settings, and takes the runtime role's `USAGE` on `platform`
away — the state the relocation found, measured rather than assumed: before
stage F neither role has a row in `pg_db_role_setting` and the runtime role has
no `USAGE` on `platform` (sprint/185.1 part B).

### Running it

```
psql -U postgres -d kumbuka -v ON_ERROR_STOP=1 -f stage-f-relocate-history-revert.sql
```

It takes the same three names as the relocation, with the same defaults, and
the `infra` wrapper runs it under `--revert`:

```
./scripts/stage-f-relocate-history.sh --revert
```

Running it when the database is already back is safe: it reports that and
changes nothing.

### If it refuses

Every refusal leaves the database unchanged — the file is one transaction.

| Message says | Meaning |
|---|---|
| **V23 is applied** | The way is forward only from here. Not a problem with this step; see above. |
| history in **both** schemas | A move stopped half-way, in one direction or the other. Decide by hand which history is real. |
| **no** history in either | There is nothing to move back. |
| the search_path is **not** the one the relocation sets | Somebody else set it. A `RESET` removes the whole setting rather than subtracting what the relocation added, so it would delete their value instead of restoring yours. |
| could not **lock** the history | A Flyway run is in progress. Stop the application container and run it again. |
