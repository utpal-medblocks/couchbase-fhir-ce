FROM maven:3.9.7-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy only pom.xml first to cache dependencies
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies (this layer will be cached)
RUN ./mvnw dependency:resolve dependency:resolve-plugins -B

# Now copy source code
COPY src ./src

# Build application (source changes won't invalidate dependency cache)
RUN ./mvnw clean package -DskipTests
RUN ls target/*

FROM eclipse-temurin:21-jre
WORKDIR /app
# Install curl for healthchecks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser
COPY --from=builder /app/target/*.jar app.jar
# Ensure logs dir exists and is writable by appuser
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
