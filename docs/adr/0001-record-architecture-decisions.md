# ADR-0001: Record architecture decisions

- Status: Accepted
- Date: 2026-06-05

## Context

This project mixes several non-trivial concerns — MCP protocol semantics,
OAuth 2.1 with two distinct OIDC client roles, a strict data-isolation
invariant for private memories, and a multi-service Docker topology. Future
contributors (and future-us) need to understand *why* a given shape was
chosen, not just *what* the code does.

## Decision

Record every architecturally significant decision as a short ADR file under
`docs/adr/`, numbered sequentially, written in English. Each ADR captures:

- Context — what forced the decision
- Decision — what we picked
- Consequences — what this commits us to and what it precludes

ADRs are append-only history. If a decision is superseded, write a new ADR
that explicitly supersedes the old one; don't rewrite history.

## Consequences

- Code reviewers can challenge or accept reasoning in writing.
- Changes that touch access-control or auth topology require updating the
  relevant ADR in the same PR.
- ADRs live in the repo, not a wiki — they version with the code.
