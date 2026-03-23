FROM eclipse-temurin:17 AS build
WORKDIR /app

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY key-gen-service/build.gradle key-gen-service/

RUN chmod +x gradlew && ./gradlew :key-gen-service:dependencies --no-daemon -q 2>/dev/null || true

COPY key-gen-service/src key-gen-service/src
RUN ./gradlew :key-gen-service:bootJar --no-daemon -x test

FROM alpine:3.19 AS otel-agent
RUN apk add --no-cache curl && \
    curl -fL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/key-gen-service/build/libs/*.jar app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
