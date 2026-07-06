# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin salus \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
RUN chown salus:salus app.jar

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

USER salus

ENTRYPOINT ["java", "-jar", "app.jar"]
