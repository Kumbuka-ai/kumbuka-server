package ai.kumbuka.tenancy;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.Team;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.UserAccount;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the first tenant-isolation layer: every tenant-scoped JPA entity must
 * carry a {@code @TenantId} field, so Hibernate appends {@code WHERE tenant_id =
 * <current>} to every ORM query/persist/delete — independently of the Postgres
 * RLS GUC.
 *
 * <p>Dropping {@code @TenantId} from any of these would silently turn the
 * entity into an RLS-only table (one layer instead of two), exactly the
 * structural weakness we want to keep out of the codebase. This test fails if
 * that annotation is removed.
 */
class TenantIdCompletenessTest {

    /** The tenant-scoped entities. All five must remain @TenantId-tagged. */
    private static final List<Class<?>> TENANT_ENTITIES = List.of(
        Memory.class, Scope.class, Team.class, TeamSettings.class, UserAccount.class);

    @Test
    void every_tenant_scoped_entity_has_a_tenant_id_field() {
        for (Class<?> entity : TENANT_ENTITIES) {
            // Walk the class hierarchy: JPA reads mapped-superclass fields as
            // part of the entity, so an inherited @TenantId counts.
            boolean hasTenantId = false;
            for (Class<?> c = entity; c != null && !hasTenantId; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(TenantId.class)) {
                        hasTenantId = true;
                        break;
                    }
                }
            }
            assertThat(hasTenantId)
                .as("%s must declare or inherit a @TenantId field so the Hibernate filter scopes "
                    + "every ORM query to the current tenant (layer 1 of tenant isolation)",
                    entity.getSimpleName())
                .isTrue();
        }
    }
}
