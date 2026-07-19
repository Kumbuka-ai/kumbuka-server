package ai.kumbuka.ratelimit;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * Shared-secret bearer token for the internal tenant-limits endpoint.
 * Same shape as the seed and erasure endpoint tokens (server-to-server
 * bearer secret, fail-loud 503 when unset); kept separate so each internal
 * operation can be rotated independently.
 */
@ConfigMapping(prefix = "kumbuka.internal.limits")
public interface LimitsEndpointConfig {

    Optional<String> token();
}
