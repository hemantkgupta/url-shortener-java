FROM gradle:8.7-jdk17 AS build
WORKDIR /build

# Copy Gradle wrapper and config first (layer cache)
COPY settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY read-api/build.gradle read-api/

# Copy source and build
COPY read-api/src read-api/src
# write-api src is needed by multi-module build graph
COPY write-api/src write-api/src
COPY write-api/build.gradle write-api/
RUN gradle :read-api:bootJar --no-daemon -x test

# Download OpenTelemetry Java agent (separate layer — cached unless version changes)
FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/read-api/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
