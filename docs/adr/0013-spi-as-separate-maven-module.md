# ADR-0013: `kumbuka-spi` as a separate Maven module, published to GitHub Packages

- Status: Accepted
- Date: 2026-06-07
- Builds on: [ADR-0011](0011-multitenancy-seam.md) (the SPI's contract)
- Driven by: the commercial **ops-console** repo (private, separate) needs
  `ai.kumbuka.tenancy.TenantResolver` and `TenantContext` as
  versioned, consumable artifacts — not "vendor them by copying".

## Context

ADR-0011 froze the `TenantResolver` SPI at v1.0.0 and named
`TenantContext` as the internal extension surface. Both interfaces lived
inside the `kumbuka-server` Quarkus app jar. That was fine while the
commercial edition was hypothetical; it stops being fine the moment a
second repository needs to depend on the contract.

Two reasons not to copy-paste the interfaces into the consumer:

- Versioned drift becomes invisible. A breaking change in the SPI must
  show up at the consumer's build, not at runtime.
- The consumer would transitively pull Quarkus / Hibernate / Jakarta /
  Postgres through the dependency chain just to get two interfaces.

## Decision

`backend/` becomes a Maven reactor with two modules:

```
backend/
├── pom.xml          ← parent (packaging=pom)
├── spi/             ← ai.kumbuka:kumbuka-spi
│   └── pom.xml
└── server/          ← ai.kumbuka:kumbuka-server (existing Quarkus app)
    └── pom.xml      ← <parent>kumbuka-parent</parent>, depends on kumbuka-spi
```

### `kumbuka-spi`

- Contains exactly two classes: `TenantResolver` (frozen v1.0.0) and
  `TenantContext` (stable but may evolve under semver).
- **Zero runtime dependencies** — no Quarkus, no JPA, no Jakarta. The
  consumer picks its own runtime.
- Source + javadoc jars attached so consumers can step into the
  contract.

### Publishing

- **GitHub Packages Maven** at
  `https://maven.pkg.github.com/Kumbuka-ai/kumbuka-server`. The release
  workflow (`spi-maven` job) authenticates with the workflow's
  `GITHUB_TOKEN` (`packages: write`) — no Maven Central / GPG / secret
  rotation for the OSS server.
- Triggered on every `v*.*.*` tag (after `verify`, before the
  github-release job). Version: tag with leading `v` stripped (so
  `v0.1.0` → `0.1.0`), set across all reactor modules via
  `versions:set`.
- **Only the SPI is published to Maven.** The server jar is not — it
  ships as the `kumbuka-backend` docker image (and is huge; nobody
  consumes it as a Maven dep).

### What stays the same

- The OSS Quarkus app keeps doing what it did. `server/` is the same
  source tree under a new parent.
- `DefaultSingleTenantResolver`, the Hibernate integration, the
  request/transaction wiring, the migration callback — all stay in
  `server/`. They are runtime, not contract.
- The Dockerfile builds via the reactor (`-pl spi,server -am`); the
  fast-jar still ships from `server/target/quarkus-app/`.
- CI passes (28 unit + 4 IT, including `CrossTenantIsolationIT`).

## Consequences

- Adding to the SPI is a deliberate act with a publication step. Breaks
  in the v1 contract require a v2 line and a new ADR superseding the
  contract clause of ADR-0011.
- The commercial ops-console (and any future consumer) depends on
  `ai.kumbuka:kumbuka-spi:<exact-version>` — no transitive drag, no
  fork, no patch.
- The server module is "internal" to this repo from a Maven perspective
  (not published as a Maven artifact). Operators consume it as a docker
  image; developers consume it via `mvn install` locally.
- Future SPI evolution (e.g. adding `getOrgClaim()` for multi-tenant
  edges) is a SPI-only PR + a tag bump — no server changes needed for
  the publication.
- The `GITHUB_TOKEN`'s default `packages: write` permission is enough.
  No external registry credentials live in repository secrets.
