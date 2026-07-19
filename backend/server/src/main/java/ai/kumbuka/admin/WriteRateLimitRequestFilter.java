package ai.kumbuka.admin;

import ai.kumbuka.ratelimit.RateLimitPolicy;
import ai.kumbuka.ratelimit.WriteRateDecision;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Console-side write-rate enforcement: applies {@link RateLimitPolicy} to
 * the {@link WriteRateLimited}-annotated write endpoints.
 *
 * <p>Priority {@code AUTHENTICATION + 50} places this AFTER token/session
 * validation (the security identity is established) and BEFORE the tenant
 * request filter at {@code AUTHENTICATION + 100} — a throttled request is
 * rejected before any per-request tenant binding work happens.
 *
 * <p>Anonymous requests pass through untouched: rejecting them is the
 * security layer's job, and unauthenticated garbage must never draw
 * rate-limit buckets (pre-authentication flood control lives at the edge,
 * not here).
 */
@Provider
@WriteRateLimited
@Priority(Priorities.AUTHENTICATION + 50)
public class WriteRateLimitRequestFilter implements ContainerRequestFilter {

    @Inject SecurityIdentity identity;
    @Inject RateLimitPolicy policy;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (identity.isAnonymous()) {
            return;
        }
        WriteRateDecision decision = policy.checkWrite(identity.getPrincipal().getName());
        if (decision.allowed()) {
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds())
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of(
                "error", "rate_limited",
                "message", "write rate limit exceeded — retry in "
                    + decision.retryAfterSeconds() + "s",
                "retryAfterSeconds", decision.retryAfterSeconds()))
            .build());
    }
}
