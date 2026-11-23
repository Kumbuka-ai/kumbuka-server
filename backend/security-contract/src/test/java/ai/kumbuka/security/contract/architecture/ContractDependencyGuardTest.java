// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enforcer rule of this module's POM ({@code contract-has-no-dependency})
 * turns the build red on a dependency outside the test scope. This test keeps
 * that red state in the tree: it copies the module's real POM and the reactor
 * POM into a temporary directory, plants one dependency outside the test scope
 * by moving {@code assertj-core} out of scope {@code test}, and runs
 * {@code validate} on the copy in a second Maven, offline against the local
 * repository of the running build. The rule under test is the one in the POM,
 * not a copy of it; the expectation is written here.
 *
 * <p>The control runs the unchanged copy: it must pass, so a red planted case
 * is red because of the planted dependency, not because the second Maven cannot
 * build at all.
 *
 * <p>Surefire passes {@code maven.home} and {@code maven.repo.local} (this
 * module's POM); without them the test fails, it does not skip.
 *
 * <p>RED STATE, observed 2026-11-23 with the assertions turned around: each
 * planted scope ({@code compile}, {@code runtime}, {@code provided}) was
 * reported, the second Maven exiting 1 with "BannedDependencies failed" and
 * naming org.assertj:assertj-core:jar:3.27.7.
 */
class ContractDependencyGuardTest {

    private static final String TEST_SCOPED_ASSERTJ = """
                        <version>3.27.7</version>
                        <scope>test</scope>
            """;

    @Test
    void the_unchanged_module_passes_the_rule(@TempDir Path work) throws Exception {
        Run run = validate(work, modulePom());

        assertThat(run.exit()).as("exit status of the second Maven, output:%n%s", run.output()).isZero();
        assertThat(run.output()).contains("BannedDependencies passed");
    }

    @ParameterizedTest(name = "assertj-core in scope {0}")
    @ValueSource(strings = {"compile", "runtime", "provided"})
    void the_checks_report_each_offence_they_exist_for(String scope, @TempDir Path work) throws Exception {
        String pom = modulePom();
        assertThat(pom.split(Pattern.quote(TEST_SCOPED_ASSERTJ), -1)).as("the POM declares assertj-core once, in scope test").hasSize(2);
        String planted = pom.replace(TEST_SCOPED_ASSERTJ, TEST_SCOPED_ASSERTJ.replace("test", scope));

        Run run = validate(work, planted);

        assertThat(run.exit()).as("exit status of the second Maven, output:%n%s", run.output()).isNotZero();
        assertThat(run.output())
                .contains("BannedDependencies failed")
                .contains("org.assertj:assertj-core:jar:3.27.7")
                .contains("kumbuka-security-contract carries no dependency outside the test scope");
    }

    private record Run(int exit, String output) {
    }

    /** Runs {@code validate} on {@code pom} as this module, beside a copy of the reactor POM as its parent. */
    private static Run validate(Path work, String pom) throws Exception {
        Path module = work.resolve("security-contract");
        Files.createDirectories(module);
        Files.copy(moduleDirectory().getParent().resolve("pom.xml"), work.resolve("pom.xml"));
        Files.writeString(module.resolve("pom.xml"), pom);

        String mavenHome = required("maven.home");
        String repository = required("maven.repo.local");
        Path log = work.resolve("maven.log");
        Process process = new ProcessBuilder(List.of(
                Path.of(mavenHome, "bin", "mvn").toString(), "-B", "-ntp", "-o",
                "-Dmaven.repo.local=" + repository,
                "-f", module.resolve("pom.xml").toString(),
                "validate"))
                .directory(module.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("the second Maven finishes").isTrue();
        return new Run(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
    }

    private static String modulePom() throws IOException {
        return Files.readString(moduleDirectory().resolve("pom.xml"));
    }

    private static Path moduleDirectory() throws IOException {
        return Path.of(required("basedir")).toRealPath();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        assertThat(value).as("system property %s, set by Surefire", property).isNotBlank();
        return value;
    }
}
