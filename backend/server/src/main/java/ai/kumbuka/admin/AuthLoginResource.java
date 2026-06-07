package ai.kumbuka.admin;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Sign-in trigger for the BFF flow (ADR-0009).
 *
 * The console's {@code /signin} page renders a top-level anchor whose href
 * is {@code /api/auth/login?return_to=<path>}. Hitting this resource is the
 * only way the browser initiates an OIDC authorization-code flow:
 *
 *   1. Unauthenticated GET → Quarkus' web-app OIDC tenant intercepts the
 *      request and 302-redirects the browser to Keycloak.
 *   2. After the user signs in, Keycloak redirects back to
 *      {@code /api/auth/callback}; Quarkus exchanges the code, sets the
 *      HttpOnly session cookie, and (because
 *      {@code restore-path-after-redirect=true}) sends the browser back to
 *      this resource — now authenticated.
 *   3. The handler below runs and 303-redirects to {@code return_to},
 *      validated against a same-origin allowlist to prevent open-redirect.
 *
 * The endpoint deliberately holds no state of its own; the OIDC flow does.
 */
@Path("/api/auth/login")
public class AuthLoginResource {

    @GET
    @Authenticated
    public Response login(@QueryParam("return_to") String returnTo) {
        String target = safeReturnTo(returnTo);
        return Response.seeOther(URI.create(target)).build();
    }

    /** Only allow same-origin, path-only return targets. */
    static String safeReturnTo(String raw) {
        if (raw == null || raw.isBlank()) return "/";
        if (!raw.startsWith("/") || raw.startsWith("//")) return "/";
        // Reject anything that looks like a scheme-relative or absolute URL.
        if (raw.contains("://")) return "/";
        return raw;
    }
}
