package ai.kumbuka.overlay;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.repo.SharedMemoryRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage-4 substance, as a reproducible integration test: a booted Quarkus
 * application resolves its guidance from a MOUNTED EXTERNAL FILE (not the
 * bundled default) and serves those entries through the real read chokepoints
 * against a real database. This is the same behaviour the docker-compose mount
 * exercises operationally, pinned here so CI proves it on every run.
 *
 * <p>The profile points {@code kumbuka.system-guidance.path} at the
 * extensibility fixture (five entries, five types), so this also proves the
 * extra, non-convention entries surface end-to-end per their own type.
 *
 * <p>Real-DB {@code @QuarkusTest}; the read tests roll back with
 * {@code @TestTransaction}.
 */
@QuarkusTest
@TestProfile(GuidanceExternalFileReadPathTest.ExternalFileProfile.class)
class GuidanceExternalFileReadPathTest {

    static final String SUBJECT = "88888888-8888-8888-8888-888888888888";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject GuidanceOverlay overlay;

    @Test
    void overlayIsBuiltFromTheExternalFile_notTheBundledDefault() {
        assertThat(overlay.activeSource()).isEqualTo(GuidanceLoader.Source.EXTERNAL);
        assertThat(overlay.activePath()).endsWith("extensibility-fixture.json");
        assertThat(overlay.version()).isEqualTo("test-1");
        assertThat(overlay.entries()).hasSize(5);
    }

    @Test
    @TestTransaction
    void extraNonConventionEntries_surfaceThroughRecall_perTheirOwnType() {
        assertThat(recallKeys(MemoryType.GLOSSARY)).containsExactly("glossary.example-term");
        assertThat(recallKeys(MemoryType.DECISION)).containsExactly("decision.example-choice");
        assertThat(recallKeys(MemoryType.CONSTRAINT)).containsExactly("constraint.example-rule");
        assertThat(recallKeys(MemoryType.STATUS)).containsExactly("status.example-phase");
        assertThat(recallKeys(MemoryType.CONVENTION))
            .containsExactly("convention.example-alpha");
    }

    @Test
    @TestTransaction
    void allFiveEntries_surfaceOnUnscopedRecall() {
        assertThat(recallKeys(null)).containsExactlyInAnyOrder(
            "convention.example-alpha", "glossary.example-term", "decision.example-choice",
            "constraint.example-rule", "status.example-phase");
    }

    @Test
    @TestTransaction
    void listShared_surfacesTheExtraEntries_perType() {
        assertThat(sharedKeys(MemoryType.GLOSSARY)).containsExactly("glossary.example-term");
        assertThat(sharedKeys(null)).contains(
            "decision.example-choice", "constraint.example-rule", "status.example-phase");
    }

    private List<String> recallKeys(MemoryType type) {
        return memories.recall(SUBJECT, "global", type, null, false)
            .stream().map(m -> m.key)
            .filter(k -> k != null && k.contains("example")).toList();
    }

    private List<String> sharedKeys(MemoryType type) {
        return sharedMemories.listShared("global", type)
            .stream().map(m -> m.key)
            .filter(k -> k != null && k.contains("example")).toList();
    }

    /** Points the overlay at the extensibility fixture on the filesystem, so the
     *  app boots from an external file rather than the bundled default. */
    public static class ExternalFileProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String path = Paths.get("src/test/resources/guidance/extensibility-fixture.json")
                .toAbsolutePath().toString();
            return Map.of("kumbuka.system-guidance.path", path);
        }
    }
}
