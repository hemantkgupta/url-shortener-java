FROM gradle:8.7-jdk17 AS build
WORKDIR /build

COPY build.gradle settings.gradle ./
COPY gradle ./gradle

COPY rls-service/build.gradle ./rls-service/
COPY rls-service/src ./rls-service/src

RUN gradle :rls-service:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /build/rls-service/build/libs/*.jar app.jar

EXPOSE 8091 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
