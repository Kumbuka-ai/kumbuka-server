package ai.kumbuka.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The repository root, for the few tests that assert against shipped files
 * rather than against the classpath.
 *
 * <p>Reading such a file off the classpath would read the wrong one: the test
 * classpath shadows {@code src/main/resources}, so a test that loaded
 * {@code application.properties} that way would certify the test profile and
 * report it as the shipped configuration. These tests therefore go to disk, and
 * they all need the same starting point.
 *
 * <p>The root is found by walking up from the working directory until
 * {@code backend} sits beside {@code deploy}, which is true of this repository
 * and of no directory inside it — so the walk cannot stop early, and it works
 * whether the runner starts in the module or in the root.
 */
public final class RepositoryRoot {

    private RepositoryRoot() {
    }

    public static Path find() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("backend")) && Files.isDirectory(p.resolve("deploy"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
            "repository root not found above " + Path.of("").toAbsolutePath());
    }
}
