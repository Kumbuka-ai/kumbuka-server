package ai.kumbuka.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Operator-declared deployment topology. There is no reliable in-process
 * way to detect how many instances of this application run behind the load
 * balancer, so the topology is an explicit operator contract: whoever
 * scales the deployment out MUST flip this to {@code multi-instance}, and
 * boot-time guards (e.g. {@link ai.kumbuka.ratelimit.RateLimitScaleGate})
 * hold the configurations that are only sound on a single instance.
 */
@ConfigMapping(prefix = "kumbuka.deployment")
public interface DeploymentConfig {

    String TOPOLOGY_SINGLE_INSTANCE = "single-instance";
    String TOPOLOGY_MULTI_INSTANCE = "multi-instance";

    /** {@code single-instance} (default) or {@code multi-instance}. */
    @WithName("topology")
    @WithDefault(TOPOLOGY_SINGLE_INSTANCE)
    String topology();
}
