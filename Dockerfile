FROM maven:3.10.2-eclipse-temurin-25@sha256:fdb6138e3d3600784c9e5f540d34d89c5c7c6074f80f611abd2c6025d9b81ef7 AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake git \
    && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
COPY scripts/install-sherpa.sh scripts/install-sherpa.sh
COPY patches/sherpa-confidence.patch patches/sherpa-confidence.patch
RUN scripts/install-sherpa.sh
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25.0.5.1_2-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
ARG APPLICATION_VERSION=0.0.0
ARG GIT_REVISION=unknown
COPY --from=build /src/target/koemoji.jar /app/koemoji.jar
LABEL org.opencontainers.image.version=$APPLICATION_VERSION \
      org.opencontainers.image.revision=$GIT_REVISION
# モデルは初回起動時に /data/models へダウンロードされる (/data ボリュームで永続化される)
ENV MODELS_DIR=/data/models VAD_MODEL=/data/models/silero_vad.onnx AUDIO_DIR=/data/audio QUEUE_DB=/data/queue.db
HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 \
  CMD ["java","-cp","/app/koemoji.jar","koemoji.HealthCheck"]
ENTRYPOINT ["java","--enable-native-access=ALL-UNNAMED","-jar","/app/koemoji.jar"]
