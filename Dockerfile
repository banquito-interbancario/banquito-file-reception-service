FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY .mvn ./.mvn
COPY mvnw mvnw.cmd ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

ENV SERVER_PORT=8084
ENV SPRING_CONFIG_IMPORT=optional:file:.env[.properties]

COPY --from=builder /workspace/target/*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]
