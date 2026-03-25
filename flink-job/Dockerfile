FROM gradle:8.7-jdk17 AS build
WORKDIR /build

# Copy root build files
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

# Copy only flink-job module
COPY flink-job/build.gradle ./flink-job/
COPY flink-job/src ./flink-job/src

RUN gradle :flink-job:shadowJar --no-daemon -x test

# Final image: just the fat JAR + Flink CLI to submit
FROM flink:1.18-java17
COPY --from=build /build/flink-job/build/libs/flink-job.jar /opt/flink-job/flink-job.jar
