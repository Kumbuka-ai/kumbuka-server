package ai.kumbuka.tenancy;

import java.util.UUID;

/**
 * Programmatic tenant binding for callers that have no incoming-request
 * context — background jobs, integration tests, and control-plane code.
 *
 * <p>This is the <strong>single source of truth</strong> for the effective
 * tenant: both the Hibernate {@code CurrentTenantIdentifierResolver} and
 * the Postgres session-GUC setter call {@link #current()}, never the
 * {@link TenantResolver} directly. That single read point is what
 * prevents a split brain where {@code bind(B)} would steer Hibernate to
 * tenant B but the Postgres GUC would still resolve to the default.
 *
 * <p>This interface is not part of the frozen SPI. The commercial
 * edition may ship its own implementation; its shape may evolve with
 * semantic-version discipline because no third-party code consumes it.
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * try (var bound = tenantContext.bind(tenantId)) {
 *     // inside this block, Hibernate filters and the Postgres GUC are
 *     // pinned to tenantId — regardless of what TenantResolver would say
 * }
 * }</pre>
 *
 * Binds nest correctly: a nested {@code bind()} pushes onto a per-thread
 * stack and the returned {@code AutoCloseable} pops it.
 */
public interface TenantContext {

    /**
     * @return the effective tenant id for this thread of execution:
     *         the most recent {@link #bind(UUID)} that has not yet been
     *         closed, or — if none is bound — whatever the configured
     *         {@link TenantResolver} returns. Never null.
     */
    UUID current();

    /**
     * Pin the effective tenant for the current thread to {@code tenantId}
     * until the returned handle is closed.
     *
     * @param tenantId non-null tenant id to bind
     * @return an {@link AutoCloseable} that unbinds the tenant when
     *         closed (idempotent and re-entrant)
     */
    AutoCloseable bind(UUID tenantId);
}
