FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml
COPY pom.xml .

# Download dependencies
RUN mvn dependency:resolve

# Copy source code
COPY src ./src

# Build application
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home warden

# Copy built JAR from builder. Globbed on purpose: this line named a jar
# (mcp-incident-automation-1.0.0.jar) that the build has never produced — the artifactId is
# incident-automation — so every docker build failed here. A glob survives the next rename too.
# .jar.original, Spring Boot's pre-repackage copy, does not match *.jar.
COPY --from=builder --chown=warden:warden /app/target/*.jar app.jar

# Probe the already-running application; do not launch a second JVM from the health check.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8080/api/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]

USER 10001

EXPOSE 8080
