package ai.kumbuka.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-tenant write-rate limit override (one row per tenant that has one; no
 * row = the deployment-wide defaults apply). Configuration only — bucket
 * fill state is ephemeral in-process limiter state and is never persisted.
 *
 * <p>Unlike the other tenant-keyed entities this one does NOT carry the
 * Hibernate {@code @TenantId} discriminator: the tenant id IS the primary
 * key (a tenant-singleton config row), and the discriminator machinery does
 * not compose with a {@code @TenantId}-typed {@code @Id}. Tenant scoping is
 * explicit instead: the only ORM write path (the internal limits endpoint)
 * keys every operation by the resolver-bound tenant and runs under
 * {@code @TenantBound}, so the row-level-security write policies on the
 * table (V19) hold as the structural backstop.
 */
@Entity
@Table(name = "tenant_limits")
public class TenantLimits extends PanacheEntityBase {

    @Id
    @Column(name = "tenant_id")
    public UUID tenantId;

    /** Per-principal write band override; all three set or all three null. */
    @Column(name = "write_burst_capacity")
    public Integer writeBurstCapacity;

    @Column(name = "write_refill_tokens")
    public Integer writeRefillTokens;

    @Column(name = "write_refill_period_seconds")
    public Integer writeRefillPeriodSeconds;

    /** Tenant-aggregate write band; null = no aggregate limit (default). */
    @Column(name = "tenant_write_burst_capacity")
    public Integer tenantWriteBurstCapacity;

    @Column(name = "tenant_write_refill_tokens")
    public Integer tenantWriteRefillTokens;

    @Column(name = "tenant_write_refill_period_seconds")
    public Integer tenantWriteRefillPeriodSeconds;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
