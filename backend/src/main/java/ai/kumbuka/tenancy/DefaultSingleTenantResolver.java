package ai.kumbuka.tenancy;

import ai.kumbuka.config.MemoryConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * OSS-edition {@link TenantResolver}: always returns the singleton tenant
 * id configured under {@code kumbuka.tenant-id}.
 *
 * <p>Registered as Quarkus' {@code @DefaultBean} so the commercial
 * edition can replace it by registering its own
 * {@code @ApplicationScoped TenantResolver} — the non-default bean wins
 * automatically. See ADR-0011.
 */
@ApplicationScoped
@DefaultBean
public class DefaultSingleTenantResolver implements TenantResolver {

    @Inject MemoryConfig config;

    @Override
    public UUID currentTenant() {
        return config.tenantId();
    }
}
