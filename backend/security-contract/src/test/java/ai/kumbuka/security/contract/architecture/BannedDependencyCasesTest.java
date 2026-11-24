// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planted cases under {@code src/test/resources/banned-dependency}, which
 * the invoker execution of this module builds on every verify, test the rule
 * this module enforces only while they carry it. A Maven project cannot take a
 * {@code jar} module as its parent, so each case carries the enforcer plugin
 * itself; this test holds that plugin block, version, execution and rule,
 * equal to the one in this module's POM, and holds the planted scope of each
 * case to its name: {@code compile}, {@code runtime} and {@code provided} must
 * be red, {@code control} carries scope {@code test} and must be green.
 *
 * <p>RED STATE, observed 2026-11-23 with the assertions of the planted cases
 * turned around: each offence below was reported.
 */
class BannedDependencyCasesTest {

    private static final Map<String, String> SCOPE_OF_CASE = Map.of(
            "compile", "compile",
            "runtime", "runtime",
            "provided", "provided",
            "control", "test");

    @Test
    void every_planted_case_carries_the_rule_of_this_module() throws IOException {
        Path module = Path.of(required("basedir")).toRealPath();
        Path cases = module.resolve("src/test/resources/banned-dependency");
        Map<String, String> poms = new LinkedHashMap<>();
        try (var directories = Files.list(cases)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                poms.put(directory.getFileName().toString(), Files.readString(directory.resolve("pom.xml")));
            }
        }

        assertThat(offences(Files.readString(module.resolve("pom.xml")), poms)).isEmpty();
    }

    @Test
    void the_checks_report_each_offence_they_exist_for() {
        String module = pom("compile", "<exclude>*:*:*:*:provided</exclude>");
        Map<String, String> poms = new LinkedHashMap<>();
        poms.put("compile", pom("compile", "<exclude>*:*:*:*:provided</exclude>"));
        poms.put("runtime", pom("runtime", "<exclude>*:*:*:*:other</exclude>"));
        poms.put("provided", pom("compile", "<exclude>*:*:*:*:provided</exclude>"));
        poms.put("control", "<project/>");
        poms.put("stray", pom("compile", "<exclude>*:*:*:*:provided</exclude>"));

        assertThat(offences(module, Map.of())).containsExactlyInAnyOrder(
                "case compile: missing",
                "case runtime: missing",
                "case provided: missing",
                "case control: missing");
        assertThat(offences(module, poms)).containsExactlyInAnyOrder(
                "case runtime: the enforcer plugin differs from the module's",
                "case provided: plants assertj-core in scope compile, not provided",
                "case control: carries no enforcer plugin",
                "case control: plants assertj-core in scope none, not test",
                "case stray: not a declared case");
        assertThat(offences("<project/>", poms)).contains("module: carries no enforcer plugin");
    }

    /** The offences of the planted case POMs, by directory name, against the module POM. */
    static List<String> offences(String modulePom, Map<String, String> casePoms) {
        List<String> offences = new ArrayList<>();
        Optional<String> rule = enforcerBlock(modulePom);
        if (rule.isEmpty()) {
            offences.add("module: carries no enforcer plugin");
        }
        for (String name : SCOPE_OF_CASE.keySet()) {
            if (!casePoms.containsKey(name)) {
                offences.add("case " + name + ": missing");
            }
        }
        for (Map.Entry<String, String> entry : casePoms.entrySet()) {
            String name = entry.getKey();
            String expectedScope = SCOPE_OF_CASE.get(name);
            if (expectedScope == null) {
                offences.add("case " + name + ": not a declared case");
                continue;
            }
            Optional<String> planted = enforcerBlock(entry.getValue());
            if (planted.isEmpty()) {
                offences.add("case " + name + ": carries no enforcer plugin");
            } else if (rule.isPresent() && !planted.get().equals(rule.get())) {
                offences.add("case " + name + ": the enforcer plugin differs from the module's");
            }
            String scope = plantedScope(entry.getValue()).orElse("none");
            if (!scope.equals(expectedScope)) {
                offences.add("case " + name + ": plants assertj-core in scope " + scope + ", not " + expectedScope);
            }
        }
        return offences;
    }

    /** The enforcer plugin from its artifactId to the end of its plugin element, whitespace collapsed. */
    private static Optional<String> enforcerBlock(String pom) {
        int start = pom.indexOf("<artifactId>maven-enforcer-plugin</artifactId>");
        if (start < 0) {
            return Optional.empty();
        }
        int end = pom.indexOf("</plugin>", start);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(pom.substring(start, end).replaceAll("\\s+", " ").strip());
    }

    /** The scope of the assertj-core dependency the case plants. */
    private static Optional<String> plantedScope(String pom) {
        int start = pom.indexOf("<artifactId>assertj-core</artifactId>");
        if (start < 0) {
            return Optional.empty();
        }
        int end = pom.indexOf("</dependency>", start);
        String dependency = pom.substring(start, end < 0 ? pom.length() : end);
        int open = dependency.indexOf("<scope>");
        int close = dependency.indexOf("</scope>");
        return open < 0 || close < open
                ? Optional.of("compile")
                : Optional.of(dependency.substring(open + "<scope>".length(), close).strip());
    }

    private static String pom(String scope, String exclude) {
        return """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.assertj</groupId>
                      <artifactId>assertj-core</artifactId>
                      <version>3.27.7</version>
                      <scope>%s</scope>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-enforcer-plugin</artifactId>
                    <version>3.5.0</version>
                    <configuration><rules><bannedDependencies><excludes>
                      %s
                    </excludes></bannedDependencies></rules></configuration>
                  </plugin></plugins></build>
                </project>
                """.formatted(scope, exclude);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        assertThat(value).as("system property %s, set by Surefire", property).isNotBlank();
        return value;
    }
}
