# ── Stage 1: builder ──────────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /build

# Download deps before copying source so this layer is cached on source changes
COPY deps.edn build.clj ./
RUN clojure -P && clojure -P -T:build

# Copy source
COPY src       src
COPY resources resources

# Pre-generate static HTML (writes to target/resources/public/)
# so it gets baked into the uberjar classpath under public/
RUN mkdir -p target/resources && \
    clojure -e "(require 'com.robotfund) (com.robotfund/generate-assets! {})"

# Compile + uberjar (build.clj copies target/resources/ if present)
RUN clojure -T:build uber

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S robotfund && adduser -S robotfund -G robotfund

WORKDIR /app

# Persistent data dirs owned by the non-root user
RUN mkdir -p /data/xtdb /data/logs && \
    chown -R robotfund:robotfund /data

# Writable scratch dir for generate-assets! called at startup
RUN mkdir -p target/resources/public && \
    chown -R robotfund:robotfund /app

COPY --from=builder --chown=robotfund:robotfund /build/target/app.jar app.jar

USER robotfund

EXPOSE 8080

ENTRYPOINT ["java", \
            "-Dbiff.env.BIFF_PROFILE=prod", \
            "-XX:-OmitStackTraceInFastThrow", \
            "-XX:+CrashOnOutOfMemoryError", \
            "-jar", "app.jar"]
