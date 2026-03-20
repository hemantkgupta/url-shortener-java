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

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/write-api/build/libs/*.jar app.jar
EXPOSE 8080 9092
ENTRYPOINT ["java", "-jar", "app.jar"]
