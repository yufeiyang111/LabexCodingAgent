# LabexAgent development sandbox

Build from the repository root:

```powershell
docker build -t labex-agent-sandbox:dev -f docker/sandbox/Dockerfile docker/sandbox
```

The image contains Java 17, Maven, Node.js/npm, Python 3, Git, ripgrep, TypeScript language server, Vue language server, and Pyright. Runtime container policy is enforced by `DockerSandboxWorker`: only the project workspace is bind-mounted, the root filesystem is read-only, network is disabled by default, and the container is removed after execution.
