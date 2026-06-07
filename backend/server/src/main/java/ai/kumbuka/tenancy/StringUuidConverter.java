package ai.kumbuka.tenancy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.UUID;

/**
 * Bridges Hibernate's String tenant identifier (Quarkus' {@code
 * TenantResolver} SPI is String-only) to the {@code uuid} Postgres
 * column type.
 *
 * <p>{@code @TenantId} field type must match what
 * {@link io.quarkus.hibernate.orm.runtime.tenant.TenantResolver#resolveTenantId()}
 * returns (String); the DB column is {@code uuid} per V1__init.sql. This
 * converter is applied per-attribute via {@code @Convert} on each
 * {@code tenantId} field so the round-trip stays explicit and grep-able.
 */
@Converter
public class StringUuidConverter implements AttributeConverter<String, UUID> {

    @Override
    public UUID convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : UUID.fromString(attribute);
    }

    @Override
    public String convertToEntityAttribute(UUID dbData) {
        return dbData == null ? null : dbData.toString();
    }
}
