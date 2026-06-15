package ai.kumbuka.version;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stamps {@code X-Kumbuka-Version} on every HTTP response. Cheap ops
 * affordance — operators can {@code curl -I <host>} to confirm which
 * backend version they're talking to without authenticating or looking
 * up image tags.
 */
@Provider
public class VersionHeaderFilter implements ContainerResponseFilter {

    public static final String HEADER = "X-Kumbuka-Version";

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String version;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        // putSingle (not add) — be idempotent if a downstream filter set it too.
        response.getHeaders().putSingle(HEADER, version);
    }
}
