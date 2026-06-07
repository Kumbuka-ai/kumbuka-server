package ai.kumbuka.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Thread-local stack of bound tenant ids. The bottom of the stack always
 * resolves to {@link TenantResolver}; explicit {@link #bind(UUID)} calls
 * push a tenant id on top and close-pops it off again.
 *
 * <p>Although the bean is {@code @ApplicationScoped}, the state is
 * thread-local, so a single instance serves all worker threads. The
 * close-returning idiom is what guarantees correctness across thread
 * reuse — the request filter unbinds in a {@code finally} block.
 *
 * <p>This class is the only place that reads {@link TenantResolver}.
 * Hibernate's {@code CurrentTenantIdentifierResolver} and the Postgres
 * GUC setter both go through {@link #current()}.
 */
@ApplicationScoped
public class RequestScopedTenantContext implements TenantContext {

    @Inject TenantResolver resolver;

    private final ThreadLocal<Deque<UUID>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public UUID current() {
        UUID bound = stack.get().peek();
        if (bound != null) {
            return bound;
        }
        UUID resolved = resolver.currentTenant();
        if (resolved == null) {
            throw new IllegalStateException(
                "TenantResolver returned null — every request must resolve a tenant");
        }
        return resolved;
    }

    @Override
    public AutoCloseable bind(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        stack.get().push(tenantId);
        return new Unbind(tenantId);
    }

    private final class Unbind implements AutoCloseable {
        private final UUID expected;
        private boolean closed;

        Unbind(UUID expected) {
            this.expected = expected;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Deque<UUID> dq = stack.get();
            UUID top = dq.peek();
            if (top == null || !top.equals(expected)) {
                // Unbinds out of order — re-pushing would corrupt the
                // stack; fail loudly instead.
                throw new IllegalStateException(
                    "tenant bind/unbind out of order: expected=" + expected
                        + " top=" + top);
            }
            dq.pop();
            if (dq.isEmpty()) {
                // Free the ThreadLocal entry when nothing is bound on
                // this thread, so a thread-pool worker doesn't retain
                // an empty Deque indefinitely.
                stack.remove();
            }
        }
    }
}
