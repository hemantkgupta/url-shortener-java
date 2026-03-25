FROM gradle:8.7-jdk17 AS build
WORKDIR /build

COPY settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY analytics-api/build.gradle analytics-api/

COPY analytics-api/src analytics-api/src
RUN gradle :analytics-api:bootJar --no-daemon -x test

# Download OpenTelemetry Java agent (separate layer — cached unless version changes)
FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/analytics-api/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
