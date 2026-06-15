package ai.kumbuka.seed;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Configuration for the internal seed-tenant endpoint (D-CORE-11).
 *
 * <p>Same shape as {@code ErasureConfig} — a server-to-server bearer secret,
 * fail-loud (503) when unset. Kept SEPARATE from the erasure token so the
 * two operations can be rotated independently and a leaked seeder secret
 * can be revoked without disrupting erasure.
 */
@ConfigMapping(prefix = "kumbuka.internal.seed")
public interface SeedConfig {

    /** Shared bearer secret. Unset → endpoint refuses with 503. */
    @WithName("token")
    Optional<String> token();
}
