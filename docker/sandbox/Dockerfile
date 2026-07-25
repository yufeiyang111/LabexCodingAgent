FROM node:20-slim

ENV DEBIAN_FRONTEND=noninteractive \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

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

RUN npm install --global \
        typescript \
        typescript-language-server \
        @vue/language-server \
        pyright

RUN useradd --create-home --uid 1001 --shell /bin/bash sandbox

WORKDIR /workspace
USER sandbox
CMD ["/bin/bash"]
