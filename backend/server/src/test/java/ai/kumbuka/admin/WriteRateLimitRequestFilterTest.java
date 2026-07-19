package ai.kumbuka.admin;

import ai.kumbuka.ratelimit.RateLimitPolicy;
import ai.kumbuka.ratelimit.WriteRateDecision;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Console-adapter contract: anonymous requests pass untouched (their
 * rejection belongs to the security layer), an allowed decision does not
 * interfere, and a throttled decision aborts with 429 + Retry-After and a
 * content-free JSON body.
 */
class WriteRateLimitRequestFilterTest {

    private SecurityIdentity identity;
    private RateLimitPolicy policy;
    private ContainerRequestContext requestContext;
    private WriteRateLimitRequestFilter filter;

    @BeforeEach
    void setUp() {
        identity = mock(SecurityIdentity.class);
        policy = mock(RateLimitPolicy.class);
        requestContext = mock(ContainerRequestContext.class);
        filter = new WriteRateLimitRequestFilter();
        filter.identity = identity;
        filter.policy = policy;
    }

    @Test
    void anonymousRequestsPassWithoutDrawingBuckets() {
        when(identity.isAnonymous()).thenReturn(true);

        filter.filter(requestContext);

        verify(policy, never()).checkWrite(anyString());
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void allowedWritePassesThrough() {
        authenticatedAs("subject-1");
        when(policy.checkWrite("subject-1")).thenReturn(WriteRateDecision.allow());

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void throttledWriteAbortsWith429AndRetryAfter() {
        authenticatedAs("subject-1");
        when(policy.checkWrite("subject-1")).thenReturn(WriteRateDecision.throttle(42));

        filter.filter(requestContext);

        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(aborted.capture());
        Response response = aborted.getValue();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("error", "rate_limited");
        assertThat(body).containsEntry("retryAfterSeconds", 42L);
    }

    private void authenticatedAs(String subject) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(subject);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
    }
}
