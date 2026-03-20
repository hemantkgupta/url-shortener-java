FROM eclipse-temurin:17 AS build
WORKDIR /app

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY analytics-worker/build.gradle analytics-worker/

RUN chmod +x gradlew && ./gradlew :analytics-worker:dependencies --no-daemon -q 2>/dev/null || true

COPY analytics-worker/src analytics-worker/src
RUN ./gradlew :analytics-worker:bootJar --no-daemon -x test

# Download OpenTelemetry Java agent (separate layer — cached unless version changes)
FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/analytics-worker/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
