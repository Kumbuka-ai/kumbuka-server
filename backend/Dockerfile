# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# backend Dockerfile — multi-stage build
#   stage 1: Maven + JDK 21 builds the Quarkus fast-jar
#   stage 2: minimal JRE 21 runtime
# ---------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /work

# Cache dependencies in a separate layer.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -ntp package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Quarkus fast-jar layout.
COPY --from=build /work/target/quarkus-app/lib/      /app/lib/
COPY --from=build /work/target/quarkus-app/*.jar     /app/
COPY --from=build /work/target/quarkus-app/app/      /app/app/
COPY --from=build /work/target/quarkus-app/quarkus/  /app/quarkus/

EXPOSE 8080
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en
USER 1000

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
