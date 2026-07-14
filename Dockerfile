# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy dependency descriptors first so this expensive layer is cached
# and only re-executed when build files change, not on every source change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon --quiet

COPY src/ src/
RUN ./gradlew bootJar -x test --no-daemon --quiet

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as non-root
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080

# UseContainerSupport  — honour cgroup memory limits (on by default in Java 11+, explicit for clarity)
# MaxRAMPercentage=75 — cap heap at 75 % of container RAM; leaves room for metaspace + threads
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]