package ai.kumbuka.boundary;

import ai.kumbuka.testsupport.RepositoryRoot;
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
 * <h2>What is searched</h2>
 *
 * <p>Everything this repository ships, and not the sources alone: the engine
 * does not come back through Java only. A line in a compose file, a route in a
 * Caddy block, an extension re-added to a POM, a different jar copied in the
 * image — each of those puts the surface back without a single import being
 * written. The list below is the search space, spelled out so a reader need not
 * derive it from {@link #SEARCHED}:
 *
 * <ul>
 *   <li>{@code backend/server/src/main/java},
 *       {@code backend/server/src/main/resources},
 *       {@code backend/spi/src/main/java} — the core's own shipped sources
 *       and configuration.</li>
 *   <li>{@code backend/pom.xml}, {@code backend/server/pom.xml},
 *       {@code backend/spi/pom.xml} — the build, where an extension returns as
 *       one dependency element.</li>
 *   <li>{@code backend/Dockerfile}, {@code backend/.dockerignore} — the image
 *       recipe, which decides what actually ships.</li>
 *   <li>{@code deploy}, {@code ops}, {@code postgres} — the operational half:
 *       compose files, Caddy blocks, upgrade SQL, shell.</li>
 *   <li>{@code .github/workflows} — the pipeline that builds and publishes.</li>
 *   <li>{@code Caddyfile}, {@code docker-compose.yml},
 *       {@code docker-compose.prod.yml}, {@code .env.example},
 *       {@code .env.prod.example}, {@code justfile},
 *       {@code sonar-project.properties}, {@code server.json} — the root.</li>
 * </ul>
 *
 * <h2>What is deliberately not searched, and why</h2>
 *
 * <p><b>Code, not prose.</b> Comments are stripped before the search, in every
 * language the space now contains: line and block comments in Java, XML
 * comments, a leading {@code --} in SQL, and a leading {@code #} everywhere
 * else. A sentence recording that the tool route was removed is exactly the
 * kind of thing this file wants people to write; a probe that reddened on it
 * would teach the opposite lesson, and would redden on its own documentation.
 *
 * <p><b>Markdown is not searched at all</b>, because a Markdown file is prose
 * end to end and has no comment syntax to strip. This is what keeps
 * {@code README.md}, {@code ops/README.md}, the {@code README.md} files under
 * {@code deploy/} and the whole of {@code docs/} out of the space: they
 * describe the product, including the MCP surface the product still has —
 * served by another service. Reddening there would forbid writing down what
 * happened.
 *
 * <p><b>Three paths inside the space are exempt by name</b>, each with its
 * reason recorded beside it in {@link #EXCLUDED}: the migration chain, the
 * operational Caddy block, and {@code server.json}. The latter two are listed
 * in the search space rather than quietly left out of it, so a reader meets the
 * exemption and its reason instead of a gap.
 *
 * <p><b>Test sources are not searched.</b> They do not ship, and they must be
 * free to name what they assert about — {@code AdminConnectorResourceTest} pins
 * the resolution of the connector URL, which is an MCP address.
 * {@code assets/} and {@code design/} are brand and design material rather than
 * a shipped artifact of this service, and {@code LICENSE} is a licence text.
 *
 * <p><b>This test excludes itself</b>, since it must name what it forbids.
 */
class MemoryEngineIsOutOfTheCoreTest {

    /** The repository root — see {@link RepositoryRoot}. */
    private static final Path REPO_ROOT = RepositoryRoot.find();

    /**
     * Everything this repository ships. The class comment above says in prose
     * what each entry is for and what is left out; this is the list itself.
     */
    private static final List<String> SEARCHED = List.of(
        "backend/server/src/main/java",
        "backend/server/src/main/resources",
        "backend/spi/src/main/java",
        "backend/pom.xml",
        "backend/server/pom.xml",
        "backend/spi/pom.xml",
        "backend/Dockerfile",
        "backend/.dockerignore",
        "deploy",
        "ops",
        "postgres",
        ".github/workflows",
        "Caddyfile",
        "docker-compose.yml",
        "docker-compose.prod.yml",
        ".env.example",
        ".env.prod.example",
        "justfile",
        "sonar-project.properties",
        "server.json");

    /**
     * Paths inside {@link #SEARCHED} that are exempt, each with the reason it
     * is exempt. A reason here is not decoration: it is what distinguishes an
     * exemption somebody took from a blind spot nobody noticed.
     */
    private static final Map<String, String> EXCLUDED = excluded();

    private static Map<String, String> excluded() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("backend/server/src/main/resources/db/migration",
            "Dead tables, untouched chain. This was a code cut: public.memory and its "
            + "siblings stay in the chain — V1 through V25 are byte-identical — and fall "
            + "later under their own migration. A probe that refused the word here would "
            + "demand a schema change nobody sanctioned.");
        m.put("ops/caddy/kumbuka.caddy",
            "The operational edge still routes the tool path, and the cut left it "
            + "standing on purpose: at the edge that route names the memory service's "
            + "surface, not a surface of this core. Where it points is a deployment "
            + "decision, taken where the topology is decided.");
        m.put("server.json",
            "The MCP registry manifest of the product. It names the connector URL an AI "
            + "client dials — again the memory service's surface — and the cut left it "
            + "standing for the same reason as the Caddy block.");
        return m;
    }

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
        assertThat(EXCLUDED.keySet())
            .allSatisfy(rel -> assertThat(REPO_ROOT.resolve(rel))
                .as("exempt path must exist — a typo here would exempt nothing, and the "
                  + "reason recorded beside it would describe a path that is not there")
                .exists());
    }

    /**
     * The second half of the red run, for the half of the search space the
     * widening added. Outside Java and XML the comment marker is a leading
     * {@code --} in SQL and a leading {@code #} everywhere else; if that
     * stripping were wrong in either direction the widened space would either
     * redden on prose or stop seeing live configuration, and both failures are
     * silent.
     */
    @Test
    void theProbeReadsOperationalFilesAsCodeAndTheirCommentsAsProse() {
        assertThat(scanText("ops/demo.caddy", "@mcp path /mcp /mcp/*\n"))
            .as("a live route in a Caddy block must be caught")
            .isNotEmpty();
        assertThat(scanText("ops/demo.caddy", "#   the backend served /mcp here once\n"))
            .as("a hash comment recording the removal must NOT be caught")
            .isEmpty();

        assertThat(scanText("demo.yml", "  - \"quarkus-mcp-server-http\"\n"))
            .as("a live extension in a compose or workflow file must be caught")
            .isNotEmpty();
        assertThat(scanText("demo.yml", "# quarkus-mcp-server-http was dropped in the cut\n"))
            .as("a hash comment in YAML must NOT be caught")
            .isEmpty();

        assertThat(scanText("deploy/demo.sql", "-- memory_recall used to read this table\n"))
            .as("a SQL comment must NOT be caught")
            .isEmpty();
        assertThat(scanText("deploy/demo.sql", "SELECT source FROM memory_recall_log;\n"))
            .as("live SQL must be caught")
            .isNotEmpty();
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
                    .map(MemoryEngineIsOutOfTheCoreTest::relative)
                    .filter(MemoryEngineIsOutOfTheCoreTest::isSearchable)
                    .forEach(p -> hits.addAll(scanText(p, read(REPO_ROOT.resolve(p)))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return hits;
    }

    private static String relative(Path f) {
        return REPO_ROOT.relativize(f).toString().replace('\\', '/');
    }

    /**
     * Whether a file inside the search space is read. Exemptions are matched on
     * the whole relative path, not by substring, so an entry naming
     * {@code server.json} cannot silently exempt some other {@code server.json}
     * deeper in the tree.
     */
    private static boolean isSearchable(String rel) {
        for (String exempt : EXCLUDED.keySet()) {
            if (rel.equals(exempt) || rel.startsWith(exempt + "/")) {
                return false;
            }
        }
        if (rel.endsWith("MemoryEngineIsOutOfTheCoreTest.java")) {
            return false;
        }
        return isCodeOrConfig(rel);
    }

    /** The kinds this probe can read as code. Markdown is absent on purpose. */
    private static boolean isCodeOrConfig(String rel) {
        String name = fileName(rel);
        return rel.endsWith(".java") || rel.endsWith(".properties")
            || rel.endsWith(".xml") || rel.endsWith(".json")
            || rel.endsWith(".yml") || rel.endsWith(".yaml")
            || rel.endsWith(".sh") || rel.endsWith(".sql")
            || rel.endsWith(".caddy") || rel.endsWith(".example")
            || name.equals("Caddyfile") || name.equals("Dockerfile")
            || name.equals("justfile") || name.equals(".dockerignore");
    }

    private static String fileName(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
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
     * parse strings, so a quoted route inside a Java string literal is still
     * seen — which is what we want, since that is how a route comes back.
     *
     * <p>Outside Java and XML only a whole comment LINE is blanked, never a
     * trailing marker on a line that also carries code. The asymmetry is
     * deliberate: a {@code #} inside a shell string or a Caddy matcher is not a
     * comment, and erring towards reading too much as code costs a false red
     * that a reader can see, while erring the other way costs a blind spot
     * nobody sees.
     */
    private static String stripComments(String path, String content) {
        if (path.endsWith(".java")) {
            String out = blankMatching(content, Pattern.compile("(?s)/\\*.*?\\*/"));
            return blankMatching(out, Pattern.compile("(?m)//.*$"));
        }
        if (path.endsWith(".xml")) {
            return blankMatching(content, Pattern.compile("(?s)<!--.*?-->"));
        }
        if (path.endsWith(".sql")) {
            return blankMatching(content, Pattern.compile("(?m)^\\s*--.*$"));
        }
        if (path.endsWith(".json")) {
            return content;
        }
        return blankMatching(content, Pattern.compile("(?m)^\\s*#.*$"));
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
}
