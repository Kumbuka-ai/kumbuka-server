package ai.kumbuka.version;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Public read-only version metadata. Used by the team-console footer + ops
 * tooling (curl) to surface the running version of the backend without
 * having to inspect container image tags. No auth required — the
 * application's *running* version is public information (an attacker who
 * can reach the backend can already fingerprint it via response headers
 * and behaviour).
 *
 * <p>{@code version} names the deployable that is actually running: it
 * sources from Quarkus' built-in {@code quarkus.application.version}
 * (populated from Maven's {@code ${project.version}} of whatever
 * application was built — this server standalone, or a downstream
 * composition that consumes this module as a dependency). Falls back to
 * {@code "unknown"} only when the JAR is launched outside the standard
 * Quarkus packaging.
 *
 * <p>{@code core} names this module's own artifact version, read from a
 * Maven-filtered resource embedded at THIS module's build time
 * ({@code META-INF/kumbuka-server.version}) — self-describing, so it can
 * never drift from what is actually on the classpath. In a standalone
 * install the two values are equal; a consumer (e.g. the console footer)
 * renders the pair only when they differ.
 */
@Path("/api/version")
@PermitAll
public class VersionResource {

    static final String CORE_VERSION_RESOURCE = "/META-INF/kumbuka-server.version";

    private static final String CORE_VERSION = readCoreVersion();

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String version;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "kumbuka-server")
    String name;

    public record VersionInfo(String name, String version, String core) {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public VersionInfo get() {
        return new VersionInfo(name, version, CORE_VERSION);
    }

    /**
     * Read the embedded core version. The file ships in the same jar as
     * this class, so a miss is a packaging defect — surfaced as a literal
     * {@code "unknown"} rather than an exception: version metadata must
     * never take the endpoint down.
     */
    static String readCoreVersion() {
        try (InputStream in = VersionResource.class.getResourceAsStream(CORE_VERSION_RESOURCE)) {
            if (in == null) {
                return "unknown";
            }
            String value = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? "unknown" : value;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
