package ai.kumbuka.mcp;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an MCP write tool as subject to the write-rate limiter. Binds
 * {@link WriteRateLimitToolInterceptor} to the annotated tool method.
 *
 * <p>Lives in the {@code mcp} package because the MCP pipeline has no
 * request-filter seam (the endpoint is served by raw Vert.x routes, not
 * JAX-RS): enforcement must ride the tool method's interceptor chain, and
 * the rejection must speak the tool-error dialect. The limiter policy
 * itself stays protocol-neutral in the {@code ratelimit} package.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RateLimitedWrite {
}
