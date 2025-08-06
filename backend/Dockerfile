FROM maven:3.9.7-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests
RUN ls target/*


FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/*.jar app.jar
# Install Docker CLI
USER root
RUN apk add --no-cache docker-cli
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
