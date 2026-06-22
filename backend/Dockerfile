# Use Maven to build the project
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy the pom.xml and source code
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Use a JRE 21 image for the runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /root/

# Copy the jar file from the builder
COPY --from=builder /app/target/face-registry-0.0.1-SNAPSHOT.jar ./api.jar

# Expose port 8080
EXPOSE 8080

# Command to run the executable
CMD ["java", "-jar", "./api.jar"]
