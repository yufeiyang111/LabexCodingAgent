# LabexAgent sandbox worker image.
# Build (from the repository root):
#   docker build -t labex-agent-sandbox:<tag> -f docker/sandbox/Dockerfile .
# Production must pin the tag/digest recorded in docker/sandbox/README.md; never deploy "latest".

FROM node:20-slim

LABEL org.opencontainers.image.title="labex-agent-sandbox" \
      org.opencontainers.image.description="Isolated non-root worker for LabexAgent command execution" \
      org.opencontainers.image.source="https://github.com/anomalyco/LabexAgent"

ENV DEBIAN_FRONTEND=noninteractive \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
    sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list 2>/dev/null || true \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        jq \
        maven \
        openjdk-17-jdk-headless \
        python3 \
        python3-pip \
        ripgrep \
        unzip \
        zip \
    && rm -rf /var/lib/apt/lists/*

# JDTLS (Java LSP) is not packaged in Debian and download.eclipse.org is heavily
# throttled from some networks. Put jdtls.tar.gz into docker/sandbox/optional-jdtls/
# before building to embed it (see docker/sandbox/README.md); without the archive
# the image ships without JDTLS and Java LSP completion stays unavailable.
COPY docker/sandbox/optional-jdtls/ /tmp/optional-jdtls/

RUN if [ -f /tmp/optional-jdtls/jdtls.tar.gz ]; then \
        mkdir -p /opt/jdtls \
        && tar -xzf /tmp/optional-jdtls/jdtls.tar.gz -C /opt/jdtls \
        && chmod -R a+rX /opt/jdtls; \
    fi \
    && rm -rf /tmp/optional-jdtls

RUN npm config set registry https://registry.npmmirror.com \
    && npm install --global \
        typescript \
        typescript-language-server \
        @vue/language-server \
        pyright \
    && npm cache clean --force

# The worker runs as an unprivileged user; HOME is injected by the runtime
# (WorkerPolicy.safeEnvironment) to a writable tmpfs-bound directory.
# uid/gid 1000 必须与控制面 backend 容器（compose user "1000:1000"）和宿主机
# workspace 目录属主（init-host.sh chown 1000:1000）对齐，否则 backend 写入的
# 目录/文件对沙箱执行用户只读，shell 内 sed -i/文件输出会 Permission denied。
# node:20-slim 自带 node 用户占用 uid 1000，先删除再重建同名 uid 的 sandbox。
RUN userdel -r node 2>/dev/null || true \
    && useradd --create-home --uid 1000 --shell /bin/bash sandbox \
    && mkdir -p /workspace \
    && chown sandbox:sandbox /workspace

WORKDIR /workspace
USER sandbox
CMD ["/bin/bash"]
