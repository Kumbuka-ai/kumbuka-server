package ai.kumbuka.version;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Vert.x equivalent of {@link VersionHeaderFilter}. The JAX-RS filter only
 * fires on responses that traverse the RESTEasy pipeline — {@code /mcp},
 * served by {@code quarkus-mcp-server-http}, uses Vert.x routes directly
 * and never reaches a {@link jakarta.ws.rs.ext.Provider}.
 *
 * <p>This route-filter runs <em>before</em> downstream handlers (priority
 * higher than 0 → runs first) and registers an end-handler that stamps
 * {@link VersionHeaderFilter#HEADER} just before the response is sent.
 * Late-stamping is the only way to add a header to a streaming response
 * (MCP responses are chunked/SSE); putting it in the early phase would
 * race the handler's own writes.
 *
 * <p>Coexists with the JAX-RS filter: both put the same header value;
 * if a request hits both pipelines (shouldn't, but defence in depth),
 * the values agree by construction so the duplicate is harmless.
 */
@ApplicationScoped
public class VersionRouteHeaderFilter {

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String version;

    @RouteFilter(100)
    public void stampVersion(RoutingContext ctx) {
        ctx.addHeadersEndHandler(v -> ctx.response().putHeader(VersionHeaderFilter.HEADER, version));
        ctx.next();
    }
}
