FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY analytics-api/build.gradle analytics-api/

RUN chmod +x gradlew && ./gradlew :analytics-api:dependencies --no-daemon -q 2>/dev/null || true

COPY analytics-api/src analytics-api/src
RUN ./gradlew :analytics-api:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/analytics-api/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
