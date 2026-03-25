FROM gradle:8.7-jdk17 AS build
WORKDIR /build

COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY cdc-worker/build.gradle ./cdc-worker/
COPY cdc-worker/src ./cdc-worker/src

RUN gradle :cdc-worker:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /build/cdc-worker/build/libs/*.jar app.jar

# Download OTEL Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
