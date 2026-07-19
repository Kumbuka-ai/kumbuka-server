package ai.kumbuka.admin;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a console write endpoint as subject to the write-rate limiter.
 * Binds {@link WriteRateLimitRequestFilter} to exactly the annotated
 * methods (JAX-RS name binding) — write entry points only, never reads.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface WriteRateLimited {
}
