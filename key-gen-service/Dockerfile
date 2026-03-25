FROM gradle:8.7-jdk17 AS build
WORKDIR /build

COPY key-gen-service/settings.gradle settings.gradle
COPY key-gen-service/build.gradle build.gradle

COPY key-gen-service/src src
RUN gradle bootJar --no-daemon -x test

FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
