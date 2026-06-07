# ADR-0004: Use MCP Streamable HTTP transport, not SSE

- Status: Accepted
- Date: 2026-06-05

## Context

The MCP specification offers two HTTP-based transports:

- **HTTP+SSE (legacy)**: two endpoints, one for client→server POSTs and a
  separate long-lived SSE stream for server→client messages.
- **Streamable HTTP (current)**: a single endpoint at `/mcp` that accepts
  POST requests; the server can optionally respond with a stream when it
  needs to push intermediate messages. Stateless by default. Supersedes SSE
  in modern MCP clients (claude.ai, Claude Desktop, MCP Inspector).

The Quarkus extension `quarkus-mcp-server-http` ships the Streamable HTTP
transport, matching the project's pinned tech stack.

## Decision

Use Streamable HTTP exclusively. The MCP endpoint is `/mcp` (POST primarily,
optional GET for stream resumption per spec). Do not implement the legacy
SSE transport.

Implementation note: the `quarkus-mcp-server-http` extension we depend on
ships **both** transports in a single artifact (legacy HTTP/SSE *and*
Streamable HTTP). The artifact name (`-http`) reflects the modern transport
family; the runtime can serve either or both, configured via extension
properties. We configure for Streamable HTTP only and leave SSE disabled.
Verify the active transport during Phase 4 (MCP Inspector handshake).

## Consequences

- One route to reason about, one route to authenticate. Bearer-token
  validation happens once per POST, not once per long-lived stream.
- Compatible with modern MCP clients out of the box.
- Older MCP clients (pre-Streamable-HTTP) will not connect. We consider this
  acceptable — claude.ai and Claude Desktop are on Streamable HTTP.
- Caddy needs no special config (no SSE keep-alive tuning, no proxy buffering
  workarounds).
