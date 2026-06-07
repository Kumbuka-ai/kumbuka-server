package ai.kumbuka.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.annotations.TenantId;
import ai.kumbuka.tenancy.StringUuidConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton-per-tenant settings row. Backs the admin Settings screen:
 * write-scope policy, optional default project scope, who-may-create-scopes.
 * The connector endpoint URL is computed (not stored) and the connector
 * secret lives in Keycloak (see ADR-0006), so neither appears here.
 */
@Entity
@Table(name = "team_settings")
public class TeamSettings extends PanacheEntityBase {

    public enum WritePolicy {
        ASK("ask"), PROJECT("project"), GLOBAL("global");
        private final String dbValue;
        WritePolicy(String v) { this.dbValue = v; }
        public String dbValue() { return dbValue; }
        public static WritePolicy fromDb(String v) {
            return switch (v) {
                case "ask"     -> ASK;
                case "project" -> PROJECT;
                case "global"  -> GLOBAL;
                default -> throw new IllegalArgumentException("unknown write policy: " + v);
            };
        }
    }

    public enum CreateScopes {
        ADMINS("admins"), MEMBERS("members");
        private final String dbValue;
        CreateScopes(String v) { this.dbValue = v; }
        public String dbValue() { return dbValue; }
        public static CreateScopes fromDb(String v) {
            return switch (v) {
                case "admins"  -> ADMINS;
                case "members" -> MEMBERS;
                default -> throw new IllegalArgumentException("unknown create-scopes policy: " + v);
            };
        }
    }

    /**
     * Surrogate PK (ADR-0011 §M4). Hibernate 6 DISCRIMINATOR multi-tenancy
     * auto-populates the {@code @TenantId} column at persist time; that
     * doesn't compose with {@code @Id}. The row stays singleton per
     * tenant via a UNIQUE constraint on {@code tenant_id}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** Tenant axis — auto-populated by Hibernate (ADR-0011). */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "write_policy", nullable = false)
    public String writePolicy;

    /**
     * Project scope to default to when {@code writePolicy = PROJECT}. May
     * become invalid (archived / deleted) at runtime — the API layer falls
     * back to ASK in that case without mutating this column (see D3).
     */
    @Column(name = "default_scope_id")
    public UUID defaultScopeId;

    @Column(name = "create_scopes", nullable = false)
    public String createScopes;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public WritePolicy getWritePolicy() { return WritePolicy.fromDb(writePolicy); }
    public void setWritePolicy(WritePolicy p) { this.writePolicy = p.dbValue(); }

    public CreateScopes getCreateScopes() { return CreateScopes.fromDb(createScopes); }
    public void setCreateScopes(CreateScopes p) { this.createScopes = p.dbValue(); }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
