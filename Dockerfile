# TixRush in one image: the React website is built, then packed inside the Spring Boot jar,
# so one container serves both the site (/) and the API (/api). Build from the repo root:
#   docker build -t tixrush .
# Three stages; only the last one (a small Java runtime + the jar) ends up in the final image.

# ---- 1. Build the website (React + Vite) -> frontend/dist ----
FROM node:22-alpine AS web
WORKDIR /app/frontend
# Dependencies first: this layer is cached until package*.json changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- 2. Build the backend jar, with the website inside it ----
FROM eclipse-temurin:21-jdk AS api
WORKDIR /app/backend
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline || true
COPY backend/src src
# Spring Boot serves everything in static/ as files: the website becomes /index.html, /assets/...
COPY --from=web /app/frontend/dist/ src/main/resources/static/
# Tests run in CI (with real throwaway services), not here.
RUN ./mvnw -B -q -DskipTests package && cp target/tixrush-*.jar /app/app.jar

# ---- 3. The image that actually runs ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S tixrush && adduser -S tixrush -G tixrush
COPY --from=api /app/app.jar app.jar
USER tixrush
# Small servers (Render free = 512 MB): let the heap use 70% of the container's memory, and use the
# simple single-threaded garbage collector, which has the least overhead for a small heap.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
# "Is it up?" Docker (and docker compose --wait) ask this every 15s. PORT is set by hosts like Render.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- "http://localhost:${PORT:-8080}/actuator/health" | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
