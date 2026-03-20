FROM eclipse-temurin:17 AS build
WORKDIR /app

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY analytics-worker/build.gradle analytics-worker/

RUN chmod +x gradlew && ./gradlew :analytics-worker:dependencies --no-daemon -q 2>/dev/null || true

COPY analytics-worker/src analytics-worker/src
RUN ./gradlew :analytics-worker:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/analytics-worker/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
