FROM eclipse-temurin:17 AS build
WORKDIR /app

# Copy Gradle wrapper and config first (layer cache)
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY read-api/build.gradle read-api/

RUN chmod +x gradlew && ./gradlew :read-api:dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build
COPY read-api/src read-api/src
# write-api src is needed by multi-module build graph
COPY write-api/src write-api/src
COPY write-api/build.gradle write-api/
RUN ./gradlew :read-api:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/read-api/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
