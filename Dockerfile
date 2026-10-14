FROM maven:3.10.0-eclipse-temurin-25@sha256:721fefa7187746ff892b2a178eb4cac3292f89a80f76ef25c04da655f88619b8 AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake git \
    && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
COPY scripts/install-sherpa.sh scripts/install-sherpa.sh
COPY scripts/sherpa-confidence.patch scripts/sherpa-confidence.patch
RUN scripts/install-sherpa.sh
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25.0.4.1_1-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
ARG APPLICATION_VERSION=0.0.0
ARG GIT_REVISION=unknown
COPY --from=build /src/target/koemoji.jar /app/koemoji.jar
LABEL org.opencontainers.image.version=$APPLICATION_VERSION \
      org.opencontainers.image.revision=$GIT_REVISION
# models are downloaded on first start into /data/models (persisted with the /data volume)
ENV MODELS_DIR=/data/models VAD_MODEL=/data/models/silero_vad.onnx AUDIO_DIR=/data/audio QUEUE_DB=/data/queue.db
HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 \
  CMD ["java","-cp","/app/koemoji.jar","koemoji.HealthCheck"]
ENTRYPOINT ["java","--enable-native-access=ALL-UNNAMED","-jar","/app/koemoji.jar"]
