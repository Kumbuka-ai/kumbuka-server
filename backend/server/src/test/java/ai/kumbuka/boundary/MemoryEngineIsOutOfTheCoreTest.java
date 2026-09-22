package ai.kumbuka.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The standing probe for the boundary this service was cut along: the memory
 * engine lives in {@code kumbuka-memory}, and the core is a management service
 * that knows about memory only that it is one participant among several.
 *
 * <p>Without a probe the boundary is a fact about one afternoon. A single
 * import, one line of properties, one re-added tool class, and the engine is
 * back in the core — compiling, passing, and nobody the wiser until the next
 * reading. So the search that was run once by hand when the cut was made runs
 * here on every build instead.
 *
 * <h2>What is searched, and what is deliberately not</h2>
 *
 * <p><b>Code, not prose.</b> Comments and javadoc are stripped before the
 * search. A sentence recording that {@code /mcp} was removed is exactly the
 * kind of thing this file wants people to write; a probe that reddened on it
 * would teach the opposite lesson, and would redden on its own documentation.
 *
 * <p><b>Migrations are excluded.</b> This was a code cut. {@code public.memory}
 * and its siblings stay in the chain as dead tables — V1 through V25 are
 * byte-identical — and fall later under their own migration. A probe that
 * refused the word there would demand a schema change nobody sanctioned.
 *
 * <p><b>This test excludes itself</b>, since it must name what it forbids.
 */
class MemoryEngineIsOutOfTheCoreTest {

    /**
     * The repository root, found by walking up from the working directory
     * until the {@code backend} module sits beside {@code deploy}.
     */
    private static final Path REPO_ROOT = repoRoot();

    /** Everything the core ships: main sources, main resources, build files. */
    private static final List<String> SEARCHED = List.of(
        "backend/server/src/main/java",
        "backend/server/src/main/resources",
        "backend/spi/src/main/java",
        "backend/server/pom.xml",
        "backend/spi/pom.xml",
        "backend/pom.xml");

    /** Dead tables, untouched chain — see the class comment. */
    private static final String EXCLUDED_DIR = "db/migration";

    /**
     * What may not appear in the core's code. Key: the name a failure should
     * say out loud. Value: the pattern.
     *
     * <p>Word boundaries on the type names so a longer identifier that merely
     * contains one is not swept up by accident.
     */
    private static final Map<String, Pattern> FORBIDDEN = forbidden();

    private static Map<String, Pattern> forbidden() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        // The six verbs of the tool surface, whatever spells them.
        m.put("a memory verb",
            Pattern.compile(
                "memory_(remember|recall|load_context|update|forget|scopes)"));
        // The tool surface's route. `kumbuka.mcp.public-url-template` is a
        // configured address of ANOTHER participant and does not match this.
        m.put("the /mcp route", Pattern.compile("/mcp\\b"));
        // The MCP server extension itself.
        m.put("the MCP server extension",
            Pattern.compile("quarkus-mcp-server|io\\.quarkiverse\\.mcp"));
        // The entities, repositories, validators and beans that went with it.
        m.put("a memory-engine type", Pattern.compile("\\b("
            + "MemoryTools|MemoryRepository|SharedMemoryRepository"
            + "|MemoryLock|MemoryType|ContentUnit|ProtectedEntryException"
            + "|ReservedNamespaceGuard|GuidanceOverlay|GuidanceLoader"
            + "|GuidanceLoadException|MemoryContentValidator|MemoryKeyValidator"
            + "|ReferenceUrlValidator|SystemKeyNamespace|SystemSubject"
            + "|ScopeStatsRefresher|ProtectedResourceMetadataResource"
            + "|ConnectorMetadataConfig"
            + ")\\b"));
        // The entity type itself, by its package-qualified name — `MemoryConfig`
        // is a management config interface and stays, so a bare `Memory` would
        // be too blunt a hammer here.
        m.put("the Memory entity", Pattern.compile("ai\\.kumbuka\\.domain\\.Memory\\b"));
        // The packages that held the surface.
        m.put("a removed package",
            Pattern.compile("ai\\.kumbuka\\.(mcp|overlay|wellknown)\\b"));
        return m;
    }

    @Test
    void theCoreCarriesNoMemoryEngineAndNoToolSurface() {
        List<String> hits = scan();

        assertThat(hits)
            .as("The memory engine left this service in sprint 188.8 and must stay out. "
              + "Each line below is a live reference in shipped code — not in a comment, "
              + "not in a migration. If one of these is deliberate, it is a decision to "
              + "take in the open: say why here, do not widen the pattern quietly.")
            .isEmpty();
    }

    /**
     * The probe's own red run, kept beside it. A guard nobody has watched fail
     * is not a guard, and the way this one fails is not obvious: it must catch
     * a live reference while ignoring the same words in a comment. Both halves
     * are asserted here, so the probe cannot rot into a pattern that matches
     * nothing.
     */
    @Test
    void theProbeSeesALiveReferenceAndIgnoresAComment() {
        String live = """
            package ai.kumbuka.demo;
            import ai.kumbuka.repo.MemoryRepository;
            class Demo { MemoryRepository memories; }
            """;
        assertThat(scanText("Demo.java", live))
            .as("a live import of a removed type must be caught")
            .isNotEmpty();

        String commented = """
            package ai.kumbuka.demo;
            /** MemoryRepository and /mcp left the core; memory_recall with them. */
            // ai.kumbuka.mcp.MemoryTools is gone too.
            class Demo { }
            """;
        assertThat(scanText("Demo.java", commented))
            .as("prose recording the removal must NOT be caught")
            .isEmpty();

        assertThatCode(MemoryEngineIsOutOfTheCoreTest::scan)
            .as("the probe must be able to read the tree it guards")
            .doesNotThrowAnyException();
        assertThat(SEARCHED)
            .allSatisfy(rel -> assertThat(REPO_ROOT.resolve(rel))
                .as("searched path must exist — a typo here would silently guard nothing")
                .exists());
    }

    // ------------------------------------------------------------------ scan

    private static List<String> scan() {
        List<String> hits = new ArrayList<>();
        for (String rel : SEARCHED) {
            Path base = REPO_ROOT.resolve(rel);
            if (!Files.exists(base)) {
                throw new IllegalStateException("searched path is missing: " + base);
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                    .filter(MemoryEngineIsOutOfTheCoreTest::isSearchable)
                    .forEach(f -> hits.addAll(
                        scanText(REPO_ROOT.relativize(f).toString(), read(f))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return hits;
    }

    private static boolean isSearchable(Path f) {
        String p = f.toString().replace('\\', '/');
        if (p.contains(EXCLUDED_DIR)) {
            return false;
        }
        if (p.endsWith("MemoryEngineIsOutOfTheCoreTest.java")) {
            return false;
        }
        return p.endsWith(".java") || p.endsWith(".properties")
            || p.endsWith(".xml") || p.endsWith(".json");
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Hits in one file's code, each as {@code path:line: what — the line}. */
    private static List<String> scanText(String path, String content) {
        String code = stripComments(path, content);
        List<String> hits = new ArrayList<>();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            for (Map.Entry<String, Pattern> rule : FORBIDDEN.entrySet()) {
                Matcher m = rule.getValue().matcher(line);
                if (m.find()) {
                    hits.add(path + ":" + (i + 1) + ": " + rule.getKey()
                        + " — " + line.strip());
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Blank out comments while keeping line numbering intact, so a hit still
     * points at the line a reader will open. Crude on purpose: it does not
     * parse strings, so a {@code "/mcp"} inside a Java string literal is still
     * seen — which is what we want, since that is how a route comes back.
     */
    private static String stripComments(String path, String content) {
        if (path.endsWith(".properties")) {
            return blankMatching(content, Pattern.compile("(?m)^\\s*#.*$"));
        }
        if (path.endsWith(".xml")) {
            return blankMatching(content, Pattern.compile("(?s)<!--.*?-->"));
        }
        if (path.endsWith(".java")) {
            String out = blankMatching(content, Pattern.compile("(?s)/\\*.*?\\*/"));
            return blankMatching(out, Pattern.compile("(?m)//.*$"));
        }
        return content;
    }

    /** Replace every match with spaces, preserving newlines and offsets. */
    private static String blankMatching(String content, Pattern p) {
        Matcher m = p.matcher(content);
        StringBuilder out = new StringBuilder(content);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }

    private static Path repoRoot() {
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
