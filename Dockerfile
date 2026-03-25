# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy Maven wrapper and pom first — Docker layer cache skips re-download
# of dependencies unless pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -q

COPY src ./src
RUN ./mvnw package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root user — reduces blast radius if the container is compromised
RUN addgroup -S courier && adduser -S courier -G courier

# Create the uploads directory and give ownership to the app user before
# switching to it — otherwise Files.createDirectories() throws AccessDeniedException.
RUN mkdir -p /app/uploads && chown -R courier:courier /app/uploads

USER courier

COPY --from=build /app/target/*.jar app.jar

# Kubernetes liveness / readiness probes hit /actuator/health
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
