FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy Gradle wrapper and config first (layer cache)
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY write-api/build.gradle write-api/

RUN chmod +x gradlew && ./gradlew :write-api:dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build
COPY write-api/src write-api/src
RUN ./gradlew :write-api:bootJar --no-daemon -x test

# Download OpenTelemetry Java agent (separate layer — cached unless version changes)
FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/write-api/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8080 9092
# Agent activated via JAVA_TOOL_OPTIONS env var in docker-compose (not baked into CMD)
ENTRYPOINT ["java", "-jar", "app.jar"]
