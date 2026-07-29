package ai.kumbuka.repo;

import ai.kumbuka.admin.ProtectedEntryExceptionMapper;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for {@link ProtectedEntryExceptionMapper}. Lifted out
 * of any QuarkusTest because the mapper is pure transform with no CDI deps
 * — a plain new-it-and-call-it test is the cheapest, fastest way to cover
 * both reason branches.
 */
class ProtectedEntryExceptionMapperTest {

    private final ProtectedEntryExceptionMapper mapper = new ProtectedEntryExceptionMapper();

    @Test
    void upsertBlocked_mapsToHttp409_withTypedCodeAndKey() {
        ProtectedEntryException ex = new ProtectedEntryException(
            ProtectedEntryException.Reason.UPSERT_BLOCKED,
            "convention.how-to-kumbuka.types",
            "key already reserved by a protected entry");

        Response r = mapper.toResponse(ex);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertThat(body)
            .containsEntry("code", "PROTECTED_UPSERT_BLOCKED")
            .containsEntry("key", "convention.how-to-kumbuka.types")
            .containsEntry("message", "key already reserved by a protected entry");
    }

    @Test
    void protectedRejection_withNullKey_mapsToHttp409_generically() {
        // A protected-row rejection may carry a null key — the mapper must accept
        // it — and the code is derived generically from the reason name (so a new
        // reason needs no mapper change).
        ProtectedEntryException ex = new ProtectedEntryException(
            ProtectedEntryException.Reason.UPDATE_BLOCKED,
            null,
            "row is protected");

        Response r = mapper.toResponse(ex);

        assertThat(r.getStatus()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertThat(body)
            .containsEntry("code", "PROTECTED_UPDATE_BLOCKED")
            .containsEntry("message", "row is protected");
        assertThat(body).containsKey("key");
        assertThat(body.get("key")).isNull();
    }
}
