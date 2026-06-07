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

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Quarkus fast-jar layout (produced under server/target).
COPY --from=build /work/server/target/quarkus-app/lib/      /app/lib/
COPY --from=build /work/server/target/quarkus-app/*.jar     /app/
COPY --from=build /work/server/target/quarkus-app/app/      /app/app/
COPY --from=build /work/server/target/quarkus-app/quarkus/  /app/quarkus/

EXPOSE 8080
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en
USER 1000

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
