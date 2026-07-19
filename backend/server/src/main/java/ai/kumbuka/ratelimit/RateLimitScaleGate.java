package ai.kumbuka.ratelimit;

import ai.kumbuka.config.DeploymentConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Set;

/**
 * Boot assertion holding the write-rate limiter's single-instance
 * precondition. The in-memory bucket store is only sound on exactly one
 * application instance: on N instances every principal gets N independent
 * buckets and the effective limit silently becomes N times the configured
 * band. There is no reliable in-process instance count, so the topology is
 * an explicit operator contract ({@code kumbuka.deployment.topology}) and
 * this gate makes the unsound combination IMPOSSIBLE to boot rather than
 * merely documented:
 *
 * <ul>
 *   <li>{@code topology=multi-instance} + {@code store=in-memory} →
 *       startup fails loud.</li>
 *   <li>{@code store=shared} → startup fails loud until a shared-store
 *       adapter implementation actually ships; selecting a store that does
 *       not exist must never silently fall back to in-memory.</li>
 *   <li>{@code topology=single-instance} + {@code store=in-memory} → the
 *       supported configuration, boots normally.</li>
 * </ul>
 *
 * Same pattern as the OIDC principal-claim boot guard: misconfiguration is
 * a refused boot with a pointed message, never a silently weakened runtime.
 */
@ApplicationScoped
public class RateLimitScaleGate {

    private static final Logger LOG = Logger.getLogger(RateLimitScaleGate.class);

    private static final Set<String> KNOWN_STORES =
        Set.of(RateLimitConfig.STORE_IN_MEMORY, RateLimitConfig.STORE_SHARED);
    private static final Set<String> KNOWN_TOPOLOGIES = Set.of(
        DeploymentConfig.TOPOLOGY_SINGLE_INSTANCE, DeploymentConfig.TOPOLOGY_MULTI_INSTANCE);

    @Inject RateLimitConfig rateLimitConfig;
    @Inject DeploymentConfig deploymentConfig;

    void onStart(@Observes StartupEvent ev) {
        verify(rateLimitConfig.store(), deploymentConfig.topology());
        LOG.infof("write-rate limiter: store=%s topology=%s default-band=%d burst / %d per %ds",
            rateLimitConfig.store(), deploymentConfig.topology(),
            rateLimitConfig.defaultBurstCapacity(), rateLimitConfig.defaultRefillTokens(),
            rateLimitConfig.defaultRefillPeriodSeconds());
    }

    /** Package-private so tests can drive every combination directly. */
    static void verify(String store, String topology) {
        String normalizedStore = normalize(store);
        String normalizedTopology = normalize(topology);
        if (!KNOWN_STORES.contains(normalizedStore)) {
            throw new IllegalStateException(
                "REFUSING TO START: unknown kumbuka.rate-limit.store '" + store
                    + "' (expected: in-memory | shared).");
        }
        if (!KNOWN_TOPOLOGIES.contains(normalizedTopology)) {
            throw new IllegalStateException(
                "REFUSING TO START: unknown kumbuka.deployment.topology '" + topology
                    + "' (expected: single-instance | multi-instance).");
        }
        if (RateLimitConfig.STORE_SHARED.equals(normalizedStore)) {
            throw new IllegalStateException(
                "REFUSING TO START: kumbuka.rate-limit.store=shared is selected, but no shared "
                    + "bucket-store implementation ships in this build yet. Scaling past one "
                    + "instance requires a shared rate-limit store; until that adapter exists, "
                    + "run a single instance with store=in-memory.");
        }
        if (DeploymentConfig.TOPOLOGY_MULTI_INSTANCE.equals(normalizedTopology)) {
            throw new IllegalStateException(
                "REFUSING TO START: kumbuka.deployment.topology=multi-instance while "
                    + "kumbuka.rate-limit.store=in-memory. An in-memory bucket store on N "
                    + "instances silently weakens every write-rate limit to N times the "
                    + "configured band. Deploy a shared rate-limit store before scaling out, "
                    + "or declare topology=single-instance.");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
