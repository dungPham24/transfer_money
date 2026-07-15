# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Debian-based (not -alpine): the alpine tag for this image is amd64-only — it has no arm64
# manifest, so `docker compose up` fails outright on Apple Silicon. This tag is multi-arch.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy dependency descriptors first so this expensive layer is cached
# and only re-executed when build files change, not on every source change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon --quiet

COPY src/ src/
RUN ./gradlew bootJar -x test --no-daemon --quiet

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as non-root
RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080

# UseContainerSupport  — honour cgroup memory limits (on by default in Java 11+, explicit for clarity)
# MaxRAMPercentage=75 — cap heap at 75 % of container RAM; leaves room for metaspace + threads
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]