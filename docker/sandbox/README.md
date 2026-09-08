# LabexAgent sandbox worker image

## 构建（仓库根目录）

```bash
docker build -t labex-agent-sandbox:opencode-2026-08-14 -f docker/sandbox/Dockerfile .
```

镜像包含：bash、curl、git、jq、zip/unzip、ripgrep、Node.js/npm（全局 typescript、typescript-language-server、@vue/language-server、pyright）、Java 17（JDK headless）、Maven、Python 3 + pip。以非 root `sandbox` 用户（uid/gid 1000，与控制面 backend 容器及宿主机 workspace 属主对齐）运行，`WORKDIR /workspace`。`PIP_BREAK_SYSTEM_PACKAGES=1` 已内置，pip 直装不再被 Debian 的 externally-managed-environment 拦截。

## 运行时约束（由 DockerSandboxWorker 强制）

- `--rm`：命令/超时/取消结束后容器删除，不遗留运行容器
- `--network none`：默认离线；只有一次性审批授权后按 run 打开 `bridge`
- `--read-only` + `/tmp:rw,noexec,nosuid`：根文件系统只读
- `--cap-drop ALL` + `no-new-privileges:true`：无 Linux capabilities
- `--memory` / `--cpus` / `--pids-limit`：来自 `labex-agent.worker.resources.*`（默认 1024MB / 1 CPU / 256 PIDs，production 默认 2048MB / 2 CPU）
- bind mount 仅当前项目 workspace 到 `/workspace`
- 环境变量 allowlist（`WorkerPolicy.safeEnvironment`）只透传 PATH 等系统变量；`HOME`/npm cache 指向 workspace 内 runtime 目录，容器内不出现 `LABEX_AGENT_JWT_SECRET`、DB 密码、Provider API key
- 仓库根 `.dockerignore` 保证 `.env` 等密钥永不进入构建上下文

## 固定 tag / digest

生产部署必须按 tag + digest 固定（`docker pull registry/...@sha256:...`），不允许 `latest` 作为生产唯一输入。构建后记录：

```bash
docker image inspect --format '{{index .RepoDigests 0}}' labex-agent-sandbox:<tag>
```

当前已验证构建（2026-08-14，WSL2 真实 Docker daemon 验收通过）：

- tag: `labex-agent-sandbox:opencode-2026-08-14`
- imageId: `sha256:c9d473e73d3f9dc4f2680211ad3e1366593498f525440298ebb35ea9a1cae445`
- 大小: 739 MB（push 到 registry 后以 `RepoDigests` 的 registry digest 为准）

## JDTLS（Java LSP）

download.eclipse.org 在国内网络被限速（~32KB/s）。如需要在镜像内置 JDTLS：

1. 断点续传下载到 `docker/sandbox/optional-jdtls/jdtls.tar.gz`：

   ```bash
   curl -fsSL -C - -o docker/sandbox/optional-jdtls/jdtls.tar.gz \
     https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz
   ```

2. 重新构建（构建自动检测并解压安装到 `/opt/jdtls`）。

该文件不存在时构建静默跳过 JDTLS：镜像不含 JDTLS，Java 项目的 LSP 智能提示暂缺（验证与构建不受影响，`mvn test` 可用）。
