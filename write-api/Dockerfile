FROM gradle:8.7-jdk17 AS build
WORKDIR /build

# Copy Gradle wrapper and config first (layer cache)
COPY settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY write-api/build.gradle write-api/

# Copy source and build
COPY write-api/src write-api/src
RUN gradle :write-api:bootJar --no-daemon -x test

# Download OpenTelemetry Java agent (separate layer — cached unless version changes)
FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/write-api/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8080 9092
# Agent activated via JAVA_TOOL_OPTIONS env var in docker-compose (not baked into CMD)
ENTRYPOINT ["java", "-jar", "app.jar"]
