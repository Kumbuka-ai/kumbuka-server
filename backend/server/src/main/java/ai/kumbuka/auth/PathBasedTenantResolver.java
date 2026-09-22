package ai.kumbuka.auth;

import io.quarkus.oidc.TenantResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Selects the OIDC tenant based on request path.
 *
 *   /api/auth/**, /api/**   → tenant "admin"  (web-app / BFF)
 *   everything else         → null (no auth; /q/health is public)
 *
 * <p>ADR-0002 split the OIDC configuration into two named tenants because the
 * core served both a bearer resource server and the console BFF. The bearer
 * half left with the memory engine, so only {@code admin} remains; the named
 * tenant is kept rather than folded into the default one, so the console's
 * configuration keys stay where every deployment already sets them.
 */
@ApplicationScoped
public class PathBasedTenantResolver implements TenantResolver {

    @Override
    public String resolve(RoutingContext context) {
        String path = context.normalizedPath();
        if (path.startsWith("/api/")) {
            return "admin";
        }
        return null;
    }
}
