# 后端运行镜像只接收已经构建好的 JAR，避免在 2GB 服务器上现场编译。
# Worker 通过本机 Docker CLI 创建一次性隔离容器，因此只复制 CLI，不复制 daemon。
FROM docker:29-cli AS docker-cli

FROM eclipse-temurin:17-jre

RUN if ! id -u 1000 >/dev/null 2>&1; then \
        useradd --system --uid 1000 --create-home --shell /usr/sbin/nologin labex; \
    fi

WORKDIR /app
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY *.jar /app/labex-agent.jar

RUN chown -R 1000:1000 /app
USER 1000:1000

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=20 -Djava.io.tmpdir=/tmp"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/labex-agent.jar"]
