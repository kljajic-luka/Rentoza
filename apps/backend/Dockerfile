# =============================================================================
# Rentoza Backend - Production Dockerfile
# =============================================================================
# Multi-stage build optimized for Google Cloud Run deployment
# - Minimal image size with Alpine JRE
# - Layer caching for faster rebuilds
# - Non-root user for security
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Install required build tools
RUN apk add --no-cache dos2unix

# Copy Maven wrapper and backend pom first (cached layer)
COPY backend/mvnw .
COPY backend/.mvn .mvn
COPY backend/pom.xml .

# Copy shared-security source so it can be installed to local Maven repo
COPY shared-security/pom.xml /shared-security/pom.xml
COPY shared-security/src /shared-security/src

# Fix line endings and make wrapper executable
RUN dos2unix mvnw && chmod +x mvnw

# Install local shared module first, then resolve backend dependencies
RUN ./mvnw -f /shared-security/pom.xml -DskipTests install -B && \
    ./mvnw dependency:go-offline -B

# Copy source code
COPY backend/src src

# Build the application (skip tests for faster build)
RUN ./mvnw package -DskipTests -B && \
    # Extract layered JAR for better caching (Spring Boot 3.x feature)
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: Run as non-root user
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy extracted layers (better Docker caching)
COPY --from=builder --chown=appuser:appgroup /app/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /app/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/target/extracted/application/ ./

# Switch to non-root user
USER appuser

# Cloud Run uses PORT environment variable (defaults to 8080)
ENV PORT=8080
EXPOSE 8080

# Health check for container orchestration
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/actuator/health || exit 1

# JVM optimization for containers
# NOTE: Do NOT set -Dspring.profiles.active here — it overrides the
# SPRING_PROFILES_ACTIVE env var set by Cloud Run. Profile selection
# is controlled by the deploy script via staging.env / prod.env.
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -Djava.security.egd=file:/dev/./urandom"

# Use Spring Boot launcher for layered JAR
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
