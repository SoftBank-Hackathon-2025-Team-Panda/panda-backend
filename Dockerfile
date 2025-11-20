# Stage 1: Build
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

COPY gradle gradle
COPY gradlew .
COPY gradlew.bat .
COPY build.gradle.kts .
COPY settings.gradle.kts .

COPY src src

RUN chmod +x ./gradlew && ./gradlew build -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# git 및 docker CLI 설치 (배포 파이프라인에서 필요)
# docker socket 마운트로 호스트의 docker daemon 사용
RUN apt-get update && apt-get install -y git docker.io && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/build/libs/*.jar app.jar

RUN useradd -m -u 1000 appuser && \
    chown -R appuser:appuser /app && \
    groupadd -f docker && \
    usermod -aG docker appuser
USER appuser

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD java -cp app.jar org.springframework.boot.loader.JarLauncher health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
