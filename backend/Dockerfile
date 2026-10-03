# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# backend Dockerfile — multi-stage build.
#
# stage 1: Maven + JDK 21 builds the Quarkus fast-jar via the reactor
#          (parent + kumbuka-spi + kumbuka-server modules — see
#          backend/pom.xml).
# stage 2: minimal JRE 21 runtime carrying just the server module's
#          fast-jar payload.
# ---------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /work

# Cache dependencies in a dedicated layer. Copy only the POMs first so
# this layer survives source changes that don't touch dependencies.
COPY pom.xml          .
COPY spi/pom.xml      spi/pom.xml
COPY server/pom.xml   server/pom.xml
RUN mvn -B -ntp -pl spi,server -am dependency:go-offline -DskipTests || true

# Sources.
COPY spi/src     spi/src
COPY server/src  server/src

# Build the reactor; the Quarkus build runs in the server module.
RUN mvn -B -ntp -pl spi,server -am package -DskipTests

# ---------------------------------------------------------------------------

# The application, and nothing else: the fast-jar layout at the root.
#
# TWO WAYS IN, ONE FILE. CI and the release never compile in this Dockerfile:
# they run the suite in the job and pass its output as a named context,
#   --build-context app=backend/server/target/quarkus-app
# which REPLACES this stage, so neither it nor the Maven stage runs and the
# image carries exactly the build the suite tested. Locally, `docker build`
# with no context builds it from the Maven stage, as before.
FROM scratch AS app
COPY --from=build /work/server/target/quarkus-app/ /

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine

# Patch the base image's OS packages before anything else lands.
#
# A base image is rebuilt on its own schedule, so it routinely carries a package
# whose fix is already published — libexpat 2.8.4-r0 against an available
# 2.8.5-r0 is what prompted this. Upgrading here closes that gap at build time
# instead of waiting for the upstream rebuild.
#
# The cost is that two builds of the same commit can differ in OS package
# versions. That is the right trade for a runtime image: it is rebuilt and
# redeployed anyway, and the alternative is shipping a known-vulnerable package
# because the tag has not moved yet.
RUN apk --no-cache upgrade
WORKDIR /app

# Quarkus fast-jar layout (produced under server/target).
COPY --from=app /lib/      /app/lib/
COPY --from=app /*.jar     /app/
COPY --from=app /app/      /app/app/
COPY --from=app /quarkus/  /app/quarkus/

EXPOSE 8080
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en
USER 1000

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
