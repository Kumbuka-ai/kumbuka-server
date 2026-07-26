package ai.kumbuka.overlay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift gate (Stage 4). The bundled default overlay resource and the seeder
 * resource carry the same guidance content today, but nothing structurally
 * keeps them equal — two separately-maintained files. This asserts that for
 * every key present in BOTH resources the content is identical. Keys present in
 * only one resource are fine: that is what lets the gate survive extension
 * (the external file / example may add entries the seeder never had).
 *
 * <p>TEMPORARY: this gate exists only while the seeder and the bundled default
 * both carry the built-in content. It dies with the seeder — when the seeded
 * rows are torn down (separate, later work) the seeder resource goes away and
 * this test goes with it.
 */
class GuidanceDefaultDriftTest {

    @Test
    void sharedKeys_haveIdenticalContent_acrossBundledDefaultAndSeeder() throws IOException {
        Map<String, String> bundled = keyToContent("/guidance/built-in-guidance.json", "entries");
        Map<String, String> seeded = keyToContent("/seed/how-to-kumbuka.json", "seeds");

        assertThat(bundled).as("bundled default carries entries").isNotEmpty();
        assertThat(seeded).as("seeder carries entries").isNotEmpty();

        for (Map.Entry<String, String> e : bundled.entrySet()) {
            if (seeded.containsKey(e.getKey())) {
                assertThat(e.getValue())
                    .as("content for key '%s' must match between the bundled default and the seeder", e.getKey())
                    .isEqualTo(seeded.get(e.getKey()));
            }
        }
    }

    private static Map<String, String> keyToContent(String resource, String arrayField) throws IOException {
        try (InputStream is = GuidanceDefaultDriftTest.class.getResourceAsStream(resource)) {
            assertThat(is).as("resource %s is on the classpath", resource).isNotNull();
            JsonNode root = new ObjectMapper().readTree(is);
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonNode n : root.get(arrayField)) {
                out.put(n.get("key").asText(), n.get("content").asText());
            }
            return out;
        }
    }
}
