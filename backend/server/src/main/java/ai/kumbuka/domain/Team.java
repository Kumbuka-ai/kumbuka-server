package ai.kumbuka.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.annotations.TenantId;
import ai.kumbuka.tenancy.StringUuidConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team")
public class Team extends PanacheEntityBase {

    @Id
    public UUID id;

    /** Tenant axis — auto-populated by Hibernate (ADR-0011). */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(nullable = false)
    public String name;

    /**
     * Canonical per-tenant routing key. Subdomain segment
     * ("acme" for "acme.kumbuka.ai") used by the SaaS resolver to look
     * up the data tenant. CE installs carry the literal {@code 'default'}.
     * Shape + uniqueness are enforced at the DB (V7); the reserved-alias
     * list is enforced in ops-console's provisioning service.
     */
    @Column(nullable = false, unique = true)
    public String alias;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;
}
