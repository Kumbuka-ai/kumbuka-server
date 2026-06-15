package ai.kumbuka.version;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Public read-only version metadata. Used by the team-console footer + ops
 * tooling (curl) to surface the running version of the backend without
 * having to inspect container image tags. No auth required — the
 * application's *running* version is public information (an attacker who
 * can reach the backend can already fingerprint it via response headers
 * and behaviour).
 *
 * <p>Sources from Quarkus' built-in {@code quarkus.application.version}
 * config (populated from Maven's {@code ${project.version}}); when not
 * set (rare — only when the JAR is launched outside the standard Quarkus
 * packaging) falls back to {@code "unknown"}.
 */
@Path("/api/version")
@PermitAll
public class VersionResource {

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String version;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "kumbuka-server")
    String name;

    public record VersionInfo(String name, String version) {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public VersionInfo get() {
        return new VersionInfo(name, version);
    }
}
