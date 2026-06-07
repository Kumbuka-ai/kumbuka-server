package ai.kumbuka.auth;

import io.quarkus.oidc.TenantResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Selects the OIDC tenant based on request path.
 *
 *   /mcp/**                 → tenant "mcp"    (bearer / resource server)
 *   /api/auth/**, /api/**   → tenant "admin"  (web-app / BFF)
 *   everything else         → null (no auth; /.well-known and /q/health
 *                                   are public)
 *
 * See ADR-0002 for why these are split into two tenants instead of one.
 */
@ApplicationScoped
public class PathBasedTenantResolver implements TenantResolver {

    @Override
    public String resolve(RoutingContext context) {
        String path = context.normalizedPath();
        if (path.startsWith("/mcp")) {
            return "mcp";
        }
        if (path.startsWith("/api/")) {
            return "admin";
        }
        return null;
    }
}
