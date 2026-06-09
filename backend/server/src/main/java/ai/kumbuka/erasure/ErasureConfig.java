package ai.kumbuka.erasure;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Configuration for the internal erase-subject endpoint (ADR-0015).
 *
 * <p>The endpoint is server-to-server only: the kumbuka-ai provider's
 * <code>OssBackendErasureClient</code> calls it during operator-driven member
 * erasure to discharge the per-data-class policy on memory content (which the
 * provider has no DB grant on).
 *
 * <p>Defaults match the contract documented on the provider side
 * (<code>ai.kumbuka.ops.tenant.OssBackendErasureClient</code>):
 * <ul>
 *   <li>{@link #token()} — shared bearer secret. <strong>Required</strong>
 *       at runtime: when unset (blank Optional), the endpoint returns 503
 *       <em>service-unavailable</em>. This is a fail-loud default so an
 *       unconfigured deploy doesn't silently accept any caller.</li>
 *   <li>{@link #tombstoneSubject()} — the sentinel value written into
 *       {@code memory.owner_subject} (shared rows) and
 *       {@code scope.created_by} for the erased member. ADR-0015 calls
 *       this the "former member" tombstone; the default value carries
 *       leading underscores so it's visibly non-human and won't collide
 *       with any real Keycloak {@code sub} (which are UUIDs).</li>
 * </ul>
 */
@ConfigMapping(prefix = "kumbuka.internal.erasure")
public interface ErasureConfig {

    /** Shared bearer secret. Unset → endpoint refuses with 503. */
    @WithName("token")
    Optional<String> token();

    /** Sentinel identifier replacing the erased member's subject in shared metadata. */
    @WithName("tombstone-subject")
    @WithDefault("__former-member__")
    String tombstoneSubject();
}
