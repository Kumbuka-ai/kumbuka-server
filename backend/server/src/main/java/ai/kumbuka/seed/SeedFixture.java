package ai.kumbuka.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Canonical seed-content bundle (D-CORE-11). Loaded once from the bundled
 * {@code /seed/how-to-kumbuka.json} resource — the file is the source of
 * truth for the protected system-seed entries planted into every new
 * tenant's global scope.
 *
 * <p>v1 seeds: the three {@code convention.how-to-kumbuka.*} entries.
 * To update the seed text or add a new seed, edit the JSON and ship a new
 * kumbuka-server release. Note: re-running the seed call does NOT propagate
 * updated text to rows that already exist — {@code MemoryRepository.seed}
 * short-circuits on an existing {@code (scope, key)} — it only plants seeds
 * into scopes that lack them.
 */
public record SeedFixture(List<Entry> seeds) {

    public record Entry(
        String scope,
        String type,
        String key,
        String content
    ) {
        public Entry {
            Objects.requireNonNull(scope, "seed.scope");
            Objects.requireNonNull(type, "seed.type");
            Objects.requireNonNull(key, "seed.key");
            Objects.requireNonNull(content, "seed.content");
        }
    }

    private static final String RESOURCE = "/seed/how-to-kumbuka.json";
    private static volatile SeedFixture cached;

    /** Load the v1 fixture from the classpath. Cached after first call. */
    public static SeedFixture v1() {
        SeedFixture local = cached;
        if (local != null) return local;
        synchronized (SeedFixture.class) {
            if (cached == null) {
                cached = loadFromClasspath();
            }
            return cached;
        }
    }

    private static SeedFixture loadFromClasspath() {
        try (InputStream is = SeedFixture.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("missing seed resource: " + RESOURCE);
            }
            // Use the project's existing Jackson configuration via a fresh
            // mapper — the fixture has no polymorphism or custom serializers,
            // so we don't need to touch the CDI-managed instance.
            ObjectMapper m = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            Wire w = m.readValue(is, Wire.class);
            return new SeedFixture(w.seeds == null ? List.of() : List.copyOf(w.seeds));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load seed fixture " + RESOURCE, e);
        }
    }

    /** Wire DTO that mirrors the JSON shape (ignores the leading _comment). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Wire {
        @JsonProperty("seeds") List<Entry> seeds;
    }
}
