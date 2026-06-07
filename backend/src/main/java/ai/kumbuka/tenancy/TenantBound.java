package ai.kumbuka.tenancy;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method as tenant-bound: the surrounding interceptor
 * ({@link TenantBindingInterceptor}) sets the Postgres session GUC
 * {@code app.tenant_id} once at the start of the method, immediately
 * after the {@code @Transactional} interceptor has opened the
 * transaction.
 *
 * <p>Class-level annotation applies to all methods. We annotate the
 * admin REST resources and the MCP tool surface — the two HTTP-facing
 * pipelines — at class level, so DB access from anywhere inside is
 * covered without per-method discipline (cardinal rule, ADR-0011 §M5).
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantBound {
    @Nonbinding boolean value() default true;
}
