// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE MAVEN STAGE OF {@code backend/Dockerfile} COPIES EVERY MODULE OF THE
 * REACTOR. A local {@code docker build} without a named build context builds
 * from that stage; a module listed in {@code backend/pom.xml} whose directory
 * the stage does not copy makes Maven abort on the missing module. CI and the
 * release replace the stage by a named context and never see it, so this test
 * is the one place in the build that does.
 *
 * <p>For each {@code <module>} of {@code backend/pom.xml}, the build stage
 * (the first {@code FROM} up to the next) must carry {@code COPY <module>/pom.xml}
 * before its first {@code RUN mvn} (the POM-only layer that caches the
 * dependencies), and {@code COPY <module>/src} before its last {@code RUN mvn}
 * (the build).
 *
 * <p>RED STATE, observed 2026-11-23 on the Dockerfile as it stood before this
 * test: the stage copied only {@code spi} and {@code server}, and the guard
 * reported the POM and the sources of {@code security-contract} and of
 * {@code security-quarkus}, four offences. The planted cases below keep each
 * offence red on every build.
 */
class DockerfileReactorGuardTest {

    @Test
    void the_maven_stage_copies_every_module_of_the_reactor() throws Exception {
        Path backend = backendDirectory();
        List<String> modules = modulesOf(Files.readString(backend.resolve("pom.xml")));
        assertThat(modules).as("the reactor lists its modules").isNotEmpty();

        assertThat(offences(modules, Files.readString(backend.resolve("Dockerfile")))).isEmpty();
    }

    @Test
    void the_checks_report_each_offence_they_exist_for() throws Exception {
        String reactor = """
                <project>
                  <modules>
                    <module>spi</module>
                    <!-- <module>commented</module> -->
                    <module>extra</module>
                  </modules>
                </project>
                """;
        List<String> modules = modulesOf(reactor);
        assertThat(modules).containsExactly("spi", "extra");

        String complete = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY pom.xml .
                COPY spi/pom.xml spi/pom.xml
                COPY extra/pom.xml extra/pom.xml
                RUN mvn -B -ntp dependency:go-offline
                COPY spi/src spi/src
                COPY extra/src extra/src
                RUN mvn -B -ntp package
                FROM scratch AS app
                """;
        assertThat(offences(modules, complete)).as("the control: every module copied").isEmpty();

        String moduleMissing = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY pom.xml .
                COPY spi/pom.xml spi/pom.xml
                RUN mvn -B -ntp dependency:go-offline
                COPY spi/src spi/src
                RUN mvn -B -ntp package
                FROM scratch AS app
                COPY extra/pom.xml extra/pom.xml
                COPY extra/src extra/src
                """;
        assertThat(offences(modules, moduleMissing)).containsExactly(
                "module extra: no COPY extra/pom.xml before the first RUN mvn of the build stage",
                "module extra: no COPY extra/src before the last RUN mvn of the build stage");

        String pomAfterDependencyLayer = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY pom.xml .
                COPY spi/pom.xml spi/pom.xml
                RUN mvn -B -ntp dependency:go-offline
                COPY extra/pom.xml extra/pom.xml
                COPY spi/src spi/src
                COPY extra/src extra/src
                RUN mvn -B -ntp package
                """;
        assertThat(offences(modules, pomAfterDependencyLayer)).containsExactly(
                "module extra: no COPY extra/pom.xml before the first RUN mvn of the build stage");

        String sourcesMissing = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY pom.xml .
                COPY spi/pom.xml spi/pom.xml
                COPY extra/pom.xml extra/pom.xml
                RUN mvn -B -ntp dependency:go-offline
                COPY spi/src spi/src
                RUN mvn -B -ntp package
                """;
        assertThat(offences(modules, sourcesMissing)).containsExactly(
                "module extra: no COPY extra/src before the last RUN mvn of the build stage");

        String parentMissing = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY spi/pom.xml spi/pom.xml
                COPY extra/pom.xml extra/pom.xml
                RUN mvn -B -ntp dependency:go-offline
                COPY spi/src spi/src
                COPY extra/src extra/src
                RUN mvn -B -ntp package
                """;
        assertThat(offences(modules, parentMissing)).containsExactly(
                "reactor: no COPY pom.xml before the first RUN mvn of the build stage");

        String noMavenRun = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY pom.xml .
                """;
        assertThat(offences(modules, noMavenRun)).containsExactly(
                "build stage: no RUN mvn");
    }

    /** The offences of the build stage of {@code dockerfile} against the reactor's {@code modules}. */
    static List<String> offences(List<String> modules, String dockerfile) {
        List<String> stage = buildStage(dockerfile);
        int firstRun = -1;
        int lastRun = -1;
        for (int i = 0; i < stage.size(); i++) {
            if (stage.get(i).matches("RUN\\s+mvn\\b.*")) {
                firstRun = firstRun < 0 ? i : firstRun;
                lastRun = i;
            }
        }
        List<String> offences = new ArrayList<>();
        if (firstRun < 0) {
            offences.add("build stage: no RUN mvn");
            return offences;
        }
        if (!copied(stage, "pom.xml", firstRun)) {
            offences.add("reactor: no COPY pom.xml before the first RUN mvn of the build stage");
        }
        for (String module : modules) {
            if (!copied(stage, module + "/pom.xml", firstRun)) {
                offences.add("module " + module + ": no COPY " + module
                        + "/pom.xml before the first RUN mvn of the build stage");
            }
            if (!copied(stage, module + "/src", lastRun)) {
                offences.add("module " + module + ": no COPY " + module
                        + "/src before the last RUN mvn of the build stage");
            }
        }
        return offences;
    }

    /** Whether a line before {@code before} copies {@code source} from the build context. */
    private static boolean copied(List<String> stage, String source, int before) {
        for (int i = 0; i < before; i++) {
            String[] words = stage.get(i).split("\\s+");
            if (words.length >= 3 && words[0].equals("COPY") && !words[1].startsWith("--")
                    && (words[1].equals(source) || words[1].equals(source + "/"))) {
                return true;
            }
        }
        return false;
    }

    /** The instructions from the first {@code FROM} up to, not including, the second; comments and blanks left out. */
    private static List<String> buildStage(String dockerfile) {
        List<String> stage = new ArrayList<>();
        boolean inStage = false;
        for (String raw : dockerfile.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("FROM ")) {
                if (inStage) {
                    break;
                }
                inStage = true;
                continue;
            }
            if (inStage) {
                stage.add(line);
            }
        }
        return stage;
    }

    /** The {@code <module>} entries of a reactor POM, read as XML so a commented-out module does not count. */
    static List<String> modulesOf(String pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
        NodeList nodes = document.getElementsByTagName("module");
        List<String> modules = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            modules.add(((Element) nodes.item(i)).getTextContent().strip());
        }
        return modules;
    }

    /** {@code backend/}, the parent of this module's directory; Surefire names the module directory as {@code basedir}. */
    private static Path backendDirectory() throws IOException {
        String basedir = System.getProperty("basedir");
        assertThat(basedir).as("Surefire sets basedir to the module directory").isNotNull();
        Path backend = Path.of(basedir).toRealPath().getParent();
        assertThat(backend.resolve("pom.xml")).as("the reactor POM").isRegularFile();
        assertThat(backend.resolve("Dockerfile")).as("the backend Dockerfile").isRegularFile();
        return backend;
    }
}
