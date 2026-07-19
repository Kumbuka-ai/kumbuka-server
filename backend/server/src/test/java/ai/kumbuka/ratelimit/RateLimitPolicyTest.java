package ai.kumbuka.ratelimit;

import ai.kumbuka.tenancy.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy-level behaviour: bucket selection, the inert-by-default
 * tenant-aggregate bucket, degradation-fail-open on store failure, and the
 * guarantee that the policy never changes non-limit outcomes.
 */
class RateLimitPolicyTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final WriteRateBand DEFAULT_BAND = new WriteRateBand(600, 120, 60);
    private static final WriteRateBand AGGREGATE_BAND = new WriteRateBand(1000, 500, 60);

    private RateLimitBucketStore store;
    private TenantLimitsProvider provider;
    private TenantResolver resolver;
    private RateLimitPolicy policy;

    @BeforeEach
    void setUp() {
        store = mock(RateLimitBucketStore.class);
        provider = mock(TenantLimitsProvider.class);
        resolver = mock(TenantResolver.class);
        policy = new RateLimitPolicy();
        policy.store = store;
        policy.limitsProvider = provider;
        policy.tenantResolver = resolver;

        when(resolver.currentTenant()).thenReturn(TENANT);
        when(provider.effectiveLimits(TENANT)).thenReturn(
            new EffectiveWriteRateLimits(DEFAULT_BAND, Optional.empty(), false));
        when(provider.defaults()).thenReturn(
            new EffectiveWriteRateLimits(DEFAULT_BAND, Optional.empty(), false));
    }

    @Test
    void allowsWhenPrincipalBucketHasTokens() {
        when(store.tryConsume(anyString(), any()))
            .thenReturn(RateLimitBucketStore.ConsumeResult.allow());

        WriteRateDecision decision = policy.checkWrite("subject-1");

        assertThat(decision.allowed()).isTrue();
        verify(store).tryConsume(eq("sub:subject-1"), eq(DEFAULT_BAND));
    }

    @Test
    void throttlesWhenPrincipalBucketIsExhausted() {
        when(store.tryConsume(startsWith("sub:"), any()))
            .thenReturn(RateLimitBucketStore.ConsumeResult.throttle(17));

        WriteRateDecision decision = policy.checkWrite("subject-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(17);
    }

    @Test
    void tenantAggregateBucketIsInertUnlessConfigured() {
        when(store.tryConsume(anyString(), any()))
            .thenReturn(RateLimitBucketStore.ConsumeResult.allow());

        policy.checkWrite("subject-1");

        // No aggregate band configured → only the principal bucket is
        // consulted; the tenant key must not be touched.
        verify(store).tryConsume(eq("sub:subject-1"), any());
        verify(store, never()).tryConsume(startsWith("tenant:"), any());
    }

    @Test
    void tenantAggregateBucketEnforcesOnceConfigured() {
        when(provider.effectiveLimits(TENANT)).thenReturn(
            new EffectiveWriteRateLimits(DEFAULT_BAND, Optional.of(AGGREGATE_BAND), false));
        when(store.tryConsume(startsWith("sub:"), any()))
            .thenReturn(RateLimitBucketStore.ConsumeResult.allow());
        when(store.tryConsume(startsWith("tenant:"), any()))
            .thenReturn(RateLimitBucketStore.ConsumeResult.throttle(30));

        WriteRateDecision decision = policy.checkWrite("subject-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(30);
        verify(store).tryConsume(eq("tenant:" + TENANT), eq(AGGREGATE_BAND));
    }

    @Test
    void failsOpenWhenTheStoreIsUnavailable() {
        when(store.tryConsume(anyString(), any())).thenThrow(
            new RateLimitBucketStore.RateLimitStoreException("store down",
                new IllegalStateException("connection refused")));

        WriteRateDecision decision = policy.checkWrite("subject-1");

        assertThat(decision.allowed())
            .as("degradation-fail-open: a broken store must never block writes")
            .isTrue();
    }

    @Test
    void fallsBackToDefaultsWhenTenantResolutionFails() {
        when(resolver.currentTenant()).thenThrow(new IllegalStateException("no tenant host"));
        when(store.tryConsume(anyString(), any()))
            .thenReturn(RateLimitBucketStore.ConsumeResult.allow());

        WriteRateDecision decision = policy.checkWrite("subject-1");

        assertThat(decision.allowed()).isTrue();
        verify(store).tryConsume(eq("sub:subject-1"), eq(DEFAULT_BAND));
    }

    @Test
    void blankSubjectIsNotThisPolicysConcern() {
        assertThat(policy.checkWrite(null).allowed()).isTrue();
        assertThat(policy.checkWrite("  ").allowed()).isTrue();
        verify(store, never()).tryConsume(anyString(), any());
    }
}
