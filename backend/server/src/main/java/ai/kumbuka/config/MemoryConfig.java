package ai.kumbuka.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.UUID;

/**
 * Typed configuration for the kumbuka app. Backed by `kumbuka.*` keys in
 * application.properties (which are in turn fed from environment variables).
 *
 * Note: the write-scope policy + create-scopes policy used to live here in
 * Phase 0–3. They moved to the {@code team_settings} table in Phase 5; the
 * admin UI manages them at runtime (handoff §D, ADR pending). Only
 * deployment-time wiring stays in this interface.
 */
@ConfigMapping(prefix = "kumbuka")
public interface MemoryConfig {

    /** Public base URL of the service (used to build resource metadata URLs). */
    @WithName("public-base-url")
    String publicBaseUrl();

    /**
     * Template for the public MCP endpoint URL shown to the team (D-CORE-4).
     * Empty (the CE default) means "{@link #publicBaseUrl()} + /mcp" — the
     * single-tenant behaviour. The SaaS image sets this to
     * {@code https://<alias>.kumbuka.ai/mcp}; the {@code <alias>} placeholder
     * is resolved from the request-bound tenant's {@code team.alias}.
     */
    @WithName("mcp.public-url-template")
    @WithDefault("")
    String mcpPublicUrlTemplate();

    /**
     * Public base URL of the identity provider. In the canonical deployment
     * Keycloak runs on its own subdomain (e.g. https://auth.kumbuka.ai), so
     * this is distinct from {@link #publicBaseUrl()}. Used to build the
     * OIDC issuer / account-console URLs that the UI and connector see.
     */
    @WithName("auth-base-url")
    String authBaseUrl();

    /** Singleton-tenant id for this edition. See ADR-0005. */
    @WithName("tenant-id")
    UUID tenantId();

    /** Keycloak realm name. Single-realm in this edition. */
    @WithName("realm")
    @WithDefault("kumbuka")
    String realm();

    /** OAuth client_id of the MCP connector. See ADR-0006. */
    @WithName("connector-client-id")
    @WithDefault("kumbuka-connector")
    String connectorClientId();

    /** Per-type entry cap in memory_load_context. */
    @WithName("load-context.per-type-limit")
    @WithDefault("20")
    int loadContextPerTypeLimit();
}
