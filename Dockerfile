FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S mailguard && adduser -S -G mailguard -h /app mailguard
WORKDIR /app
USER mailguard
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/mailguard.jar"]

FROM runtime AS packaged
COPY --chown=mailguard:mailguard target/mailguard-0.0.1-SNAPSHOT.jar /app/mailguard.jar

FROM runtime AS source
COPY --from=build --chown=mailguard:mailguard /src/target/mailguard-0.0.1-SNAPSHOT.jar /app/mailguard.jar