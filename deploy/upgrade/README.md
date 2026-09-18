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
| **Fresh installation** | Install and start normally, let the chain run to completion, then run it **once**. |
| Already ran it | Nothing. A second run reports that the history is in place and changes nothing. |

The application may keep serving while it runs. It touches the history table and
two role settings, and a running image reads neither until it next starts. Do
not run it in the middle of a deploy: it refuses while a Flyway run holds the
history, which is the correct answer, not a problem to work around.

### Running it

```
psql -U postgres -d kumbuka -v ON_ERROR_STOP=1 -f stage-f-relocate-history.sql
```

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

### Why it exists

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
