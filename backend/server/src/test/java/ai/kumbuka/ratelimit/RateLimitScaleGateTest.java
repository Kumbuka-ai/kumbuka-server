package ai.kumbuka.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scale-gate boot assertion, exercised over every configuration
 * combination. The red cases are the rot-probes: remove the gate (or
 * weaken its condition) and these tests go red — the unsound boot must
 * REFUSE, not merely warn.
 */
class RateLimitScaleGateTest {

    @Test
    void singleInstanceWithInMemoryStoreBootsNormally() {
        assertThatCode(() -> RateLimitScaleGate.verify("in-memory", "single-instance"))
            .doesNotThrowAnyException();
    }

    @Test
    void multiInstanceWithInMemoryStoreRefusesToBoot() {
        assertThatThrownBy(() -> RateLimitScaleGate.verify("in-memory", "multi-instance"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("multi-instance")
            .hasMessageContaining("in-memory")
            .hasMessageContaining("N times the configured band");
    }

    @Test
    void sharedStoreRefusesToBootWhileNoImplementationShips() {
        // Selecting a store that does not exist must never silently fall
        // back to in-memory — that would BE the silent scale-open.
        assertThatThrownBy(() -> RateLimitScaleGate.verify("shared", "single-instance"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("no shared bucket-store implementation");
        assertThatThrownBy(() -> RateLimitScaleGate.verify("shared", "multi-instance"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START");
    }

    @Test
    void unknownValuesRefuseToBoot() {
        assertThatThrownBy(() -> RateLimitScaleGate.verify("redis", "single-instance"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown kumbuka.rate-limit.store");
        assertThatThrownBy(() -> RateLimitScaleGate.verify("in-memory", "cluster"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown kumbuka.deployment.topology");
        assertThatThrownBy(() -> RateLimitScaleGate.verify(null, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valuesAreNormalizedBeforeComparison() {
        assertThatCode(() -> RateLimitScaleGate.verify(" In-Memory ", " SINGLE-INSTANCE "))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> RateLimitScaleGate.verify("In-Memory", "Multi-Instance"))
            .isInstanceOf(IllegalStateException.class);
    }
}
