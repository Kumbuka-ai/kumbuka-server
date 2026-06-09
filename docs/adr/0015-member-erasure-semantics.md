# ADR-0015: Member erasure semantics (disable vs. erase)

- Status: Accepted
- Date: 2026-06-09
- Builds on: [ADR-0003](0003-private-scope-isolation-at-repository-layer.md),
  [ADR-0008](0008-agent-authorship-from-source-channel.md)
- Driven by: the public docs needed to state what happens to a member's memory
  when they leave. **Disable** was already ratified (concept §6); **erasure** was
  carried as an open TBD. This ADR ratifies erasure so the public-facing
  documentation rests on a recorded decision rather than a placeholder.

## Context

Two operations end a member's participation, and they are not the same thing:

- **Disable** suspends an account — the person can no longer sign in or reach the
  MCP surface — but destroys nothing. This is ratified and unchanged (concept §6):
  a disabled member's **private memory is left untouched and remains theirs**, and
  re-enabling restores access. Disable is reversible.

- **Erasure** is the right-to-erasure / account-deletion counterpart, and its
  behavior was undecided. The hard questions were: what exactly is deleted, what
  happens to entries the member authored in **shared** scopes (which the rest of
  the team relies on), and whether there is any grace period before deletion is
  irreversible.

Erasure touches two invariants already settled elsewhere: the private-memory
guarantee (ADR-0003) and server-derived authorship (ADR-0008). The decision must
not weaken either.

## Decision

**Erasure removes the member's account and their entire private scope.** The
private content is gone — deleted, not suspended. Nothing of the member's private
scope survives erasure. This is the deliberate opposite of disable.

How erasure treats what the member touched:

1. **Private memory — deleted in full.** The member's `private` scope and all its
   entries are removed. Consistent with ADR-0003, the private scope was only ever
   reachable by its owner through the MCP surface; erasure removes the container
   and its rows outright.

2. **Shared entries the member authored — retained, authorship anonymized.**
   Entries in `global` / `project` scopes are **kept**, so team knowledge the
   group depends on is not lost when a person leaves. Their authorship is
   **reassigned server-side to a tombstone identity** (e.g. *former member*). This
   is consistent with ADR-0008: authorship is a server-derived fact, never a
   client-supplied flag, so anonymizing it on erasure is a normal server-side
   provenance update, not a special case. The entry content is unchanged; only the
   author attribution is replaced.

3. **Grace window — reversible, then permanent.** Erasure is **reversible for a
   short, configurable retention period (default 30 days)**. During the window the
   account and private scope can be restored; after it elapses, deletion is
   permanent. The retention period is configuration, not a hardcoded constant.

## Consequences

- **Right-to-erasure is satisfied** without collateral loss of shared team
  knowledge: the personal data (the private scope, the identity link on shared
  authorship) is removed; the team's curated decisions/conventions/constraints
  remain, attributed to a tombstone.
- **The private guarantee is unaffected.** Erasure only ever deletes the owner's
  own private rows; no admin/console/team path gains a route to private content
  at any point (ADR-0003 holds).
- **Authorship stays server-truth.** The tombstone reassignment runs in the same
  server-derived-authorship path as every other write (ADR-0008); a client cannot
  trigger or forge it.
- **Implementation is forward-looking.** The backend does not yet implement
  erasure; this ADR fixes the contract the implementation must meet. A likely
  shape: soft-delete (mark erased + start the retention clock) on request, a
  scheduled hard-delete after the window, and the authorship reassignment applied
  atomically with the private-scope deletion. The retention period is exposed as
  configuration (e.g. `kumbuka.erasure.retention`), defaulting to 30 days.
- **Disable is now clearly contrasted with erasure** in the documentation, rather
  than erasure being an unstated gap.

## Notes

This decision is mirrored in the public-facing documentation
(`kumbuka-ai/kumbuka` → `docs/security.md`, "Disable vs. erasure"), which this ADR
now backs. The `platform-specs` ADR mirror is older (it stops at ADR-0011); the
authoritative ADR set lives here in `kumbuka-server/docs/adr/`.
