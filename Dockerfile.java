# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl "$MODULE" -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG MODULE
COPY --from=build /build/${MODULE}/target/*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
