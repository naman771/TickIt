# Multi-stage build for TickIt Spring Boot Application
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn clean package -DskipTests

# Spring Boot's extracted layout avoids nested-JAR loading on each cold start.
# Keep the ordinary JVM compiler, schema validation and eager bean initialization.
RUN java -Djarmode=tools -jar target/tickit-1.0.0-SNAPSHOT.jar extract \
    --destination target/runtime --application-filename app.jar

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/runtime/ ./

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
