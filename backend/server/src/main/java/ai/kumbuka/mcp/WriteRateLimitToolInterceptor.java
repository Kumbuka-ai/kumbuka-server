package ai.kumbuka.mcp;

import ai.kumbuka.ratelimit.RateLimitPolicy;
import ai.kumbuka.ratelimit.WriteRateDecision;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * MCP-side write-rate enforcement: applies
 * {@link ai.kumbuka.ratelimit.RateLimitPolicy} to the
 * {@link RateLimitedWrite}-annotated write tools.
 *
 * <p>Priority {@code PLATFORM_BEFORE + 150} places this BEFORE the
 * {@code @Transactional} interceptor ({@code +200}) and the tenant-binding
 * interceptor ({@code +300}): a throttled tool call is rejected after
 * token validation (the HTTP auth policy on the MCP path has already run)
 * but before a transaction is opened or any tenant-resolution work
 * happens.
 *
 * <p>The MCP wire has no HTTP status channel, so the rejection is a
 * {@link ToolCallException} — the established idiom for a clean tool error
 * ({@code isError:true}) carrying a human-readable reason and a retry
 * hint, instead of a bare internal error.
 */
@Interceptor
@RateLimitedWrite
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 150)
public class WriteRateLimitToolInterceptor {

    @Inject SecurityIdentity identity;
    @Inject RateLimitPolicy policy;

    @AroundInvoke
    public Object enforce(InvocationContext ctx) throws Exception {
        if (!identity.isAnonymous()) {
            WriteRateDecision decision = policy.checkWrite(identity.getPrincipal().getName());
            if (!decision.allowed()) {
                throw new ToolCallException(
                    "write rate limit exceeded — retry in " + decision.retryAfterSeconds()
                        + " seconds");
            }
        }
        return ctx.proceed();
    }
}
