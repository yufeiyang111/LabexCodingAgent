package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.CancellationToken;
import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.impl.RunCommandTool;
import com.labex.labexagent.tool.impl.RunTestsTool;
import com.labex.service.ProjectTerminalService;
import com.labex.entity.StudentProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in acceptance test for the locally installed WSL bubblewrap sandbox. */
@EnabledIfSystemProperty(named = "labex.wsl.smoke", matches = ".+")
class WslSandboxWorkerSmokeTest {

    @TempDir
    Path workspace;

    @Test
    void gracefullyCancelsOneCommandBeforeImmediatelyStartingTheNext() throws Exception {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-cancellation-recovery", workspace);
        Files.writeString(workspace.resolve(".labex-acceptance-command-hold.cjs"), "setTimeout(() => {}, 40000);\n");
        ProcessExecutionRequest longRequest = new ProcessExecutionRequest(
                List.of("node", ".labex-acceptance-command-hold.cjs"),
                workspace, Duration.ofSeconds(40), 10_000);
        ControlledCancellationToken token = new ControlledCancellationToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<ProcessExecutionResult> first = executor.submit(() -> worker.execute(run, longRequest, token));
            Thread.sleep(750);
            assertTrue(!first.isDone(), "the node hold command exited before cancellation");
            token.cancel();
            ProcessExecutionResult cancelled = first.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.CANCELLED, cancelled.status(), cancelled::output);

            ProcessExecutionResult next = worker.execute(run, new ProcessExecutionRequest(
                    List.of("/bin/bash", "-lc", "printf second > second.marker"),
                    workspace, Duration.ofSeconds(15), 10_000), CancellationToken.none());
            assertTrue(next.succeeded(), next::output);
            assertEquals("second", Files.readString(workspace.resolve("second.marker"), StandardCharsets.UTF_8));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executesWithOnlyTheWorkspaceBoundAndNoHostDriveVisible() throws Exception {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-smoke", workspace);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/bash", "-lc",
                        "test ! -e /mnt/c && test ! -e /mnt/d && test ! -e /proc/1/root/mnt/c && test ! -e /proc/1/root/mnt/d && mkdir -p capability-probe && ! mount -t tmpfs tmpfs capability-probe && node --version && git --version && python3 --version && java -version && mvn -v && command -v typescript-language-server && typescript-language-server --version && command -v vue-language-server && vue-language-server --version && command -v pyright-langserver && command -v jdtls && jdtls --help >/dev/null && printf workspace-only > result.txt && cat result.txt"),
                workspace, Duration.ofSeconds(30), 10_000);

        var result = worker.execute(run, request, CancellationToken.none());

        assertTrue(result.succeeded(), result::output);
        assertTrue(result.output().endsWith("workspace-only"), result::output);
        assertEquals("workspace-only", Files.readString(workspace.resolve("result.txt"), StandardCharsets.UTF_8));
    }


    @Test
    void returnsOnlyAfterWslReleasesTheWorkspaceMount() throws Exception {
        Path commandWorkspace = Files.createDirectories(workspace.resolve("quoted-command-workspace"));
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-quoted-write", commandWorkspace, true);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/bash", "--noprofile", "--norc", "-lc",
                        "printf '%s' \"$HOME\" > \"shell output.txt\" && test -f \"shell output.txt\" && printf internal-present"),
                commandWorkspace, Duration.ofSeconds(30), 10_000);

        ProcessExecutionResult result = worker.execute(run, request, CancellationToken.none());

        assertTrue(result.succeeded(), result::output);
        assertTrue(result.output().contains("internal-present"), result::output);
        assertEquals("/workspace/.labex-agent/runtime/home",
                Files.readString(commandWorkspace.resolve("shell output.txt"), StandardCharsets.UTF_8));
        deleteRecursively(commandWorkspace);
        assertTrue(Files.notExists(commandWorkspace), "WSL worker returned before its workspace mount was released");
    }

    @Test
    void executesOpenCodeShellFixtureThroughTheRealWslWorker() throws Exception {
        copyFixtureIntoWorkspace();
        RecordingWslSandboxWorker worker = new RecordingWslSandboxWorker(new LocalProcessExecutor(), "Debian");
        AgentExecutionProperties properties = new AgentExecutionProperties();
        RunCommandTool shell = new RunCommandTool(worker, properties);
        AgentContext context = new AgentContext(
                "wsl-shell-fixture", 1, null, "wsl-shell-fixture", 82L, workspace,
                new ArrayList<>(), 0);

        ToolResult install = shell.execute(context, shellArgs("cd frontend&&npm install"));
        assertTrue(install.isSuccess(), install::getContent);
        assertTrue(install.getContent().contains("status=succeeded"), install::getContent);
        assertTrue(Files.isRegularFile(workspace.resolve("frontend/node_modules/is-number/package.json")),
                () -> "npm install did not materialize the fixture dependency:\n" + install.getContent());

        JsonObject buildArgs = shellArgs("npm run build");
        buildArgs.addProperty("workdir", "frontend");
        ToolResult build = shell.execute(context, buildArgs);
        assertTrue(build.isSuccess(), build::getContent);
        assertTrue(build.getContent().contains("frontend build passed"), build::getContent);

        JsonObject backendTestArgs = shellArgs("mvn -q test");
        backendTestArgs.addProperty("workdir", "backend");
        // Maven 冷启动可能较慢，fixture 测试使用 300 秒 Shell 超时。
        backendTestArgs.addProperty("timeout", 300_000);
        ToolResult backendTest = shell.execute(context, backendTestArgs);
        assertTrue(backendTest.isSuccess(), backendTest::getContent);
        assertTrue(Files.isRegularFile(workspace.resolve("backend/target/backend-fixture-test.marker")),
                () -> "mvn test did not run the fixture test:\\n" + backendTest.getContent());

        ToolResult quotedWrite = shell.execute(context,
                shellArgs("printf '%s' \"$HOME\" > \"shell output.txt\""));
        assertTrue(quotedWrite.isSuccess(), quotedWrite::getContent);
        assertEquals(List.of("/bin/bash", "--noprofile", "--norc", "-lc",
                "printf '%s' \"$HOME\" > \"shell output.txt\""), worker.lastRequest.command());
        assertEquals(workspace.toAbsolutePath().normalize(), worker.lastRequest.workingDirectory());
        assertTrue(worker.lastRun.policy().networkEnabled());
        assertTrue(Files.exists(workspace.resolve("shell output.txt")),
                () -> quotedWrite.getContent() + "\nrequest=" + worker.lastRequest + "\nrun=" + worker.lastRun);
        assertTrue(Files.readString(workspace.resolve("shell output.txt"), StandardCharsets.UTF_8).length() > 0);

        ToolResult largeOutput = shell.execute(context,
                shellArgs("node -e 'process.stdout.write(\"x\".repeat(70000))'"));
        String artifactPath = ".labex-agent/artifacts/task-82/shell-standalone.log";
        assertTrue(largeOutput.isSuccess(), largeOutput::getContent);
        assertTrue(largeOutput.isExecutionOutputTruncated(), largeOutput::getContent);
        assertEquals(artifactPath, largeOutput.getExecutionOutputPath());
        assertTrue(largeOutput.getContent().contains("output_path=" + artifactPath), largeOutput::getContent);
        assertTrue(!largeOutput.getContent().contains(workspace.toString()), largeOutput::getContent);
        Path fullOutputArtifact = workspace.resolve(artifactPath);
        assertTrue(Files.isRegularFile(fullOutputArtifact), largeOutput::getContent);
        assertTrue(Files.size(fullOutputArtifact) >= 70_000L, () -> "artifact=" + fullOutputArtifact);

        ToolResult pipeline = shell.execute(context,
                shellArgs("cat \"shell output.txt\" | sed 's#/#_#g' > \"pipe output.txt\" && cat \"pipe output.txt\""));
        assertTrue(pipeline.isSuccess(), pipeline::getContent);
        assertTrue(Files.exists(workspace.resolve("pipe output.txt")), pipeline::getContent);
        assertTrue(pipeline.getContent().contains("workspace_.labex-agent_runtime_home"), pipeline::getContent);


        ToolResult failingTest = shell.execute(context, shellArgs("npm test"));
        assertTrue(!failingTest.isSuccess(), "fixture must begin with a real failing test: " + failingTest.getContent());
        assertTrue(failingTest.getContent().contains("exit=1"), failingTest::getContent);

        Files.writeString(workspace.resolve("src/broken.js"), """
                export function add(left, right) {
                  return left + right;
                }
                """, StandardCharsets.UTF_8);
        ToolResult repairedTest = shell.execute(context, shellArgs("npm test"));
        assertTrue(repairedTest.isSuccess(), repairedTest::getContent);
        assertTrue(repairedTest.getContent().contains("fixture test passed"), repairedTest::getContent);
    }

    @Test
    void executesRunTestsAndManagedTerminalThroughTheSameRealWslShell() throws Exception {
        copyFixtureIntoWorkspace();
        RecordingWslSandboxWorker worker = new RecordingWslSandboxWorker(new LocalProcessExecutor(), "Debian");
        AgentExecutionProperties properties = new AgentExecutionProperties();
        AgentContext context = new AgentContext(
                "wsl-shared-shell", 1, null, "wsl-shared-shell", 83L, workspace,
                new ArrayList<>(), 0);

        ToolResult tests = new RunTestsTool(worker).execute(context, new JsonObject());

        assertTrue(!tests.isSuccess(), "fixture starts red and must execute a real test: " + tests.getContent());
        assertTrue(tests.getContent().contains("shell=bash"), tests::getContent);
        assertEquals(List.of("/bin/bash", "--noprofile", "--norc", "-lc", "'npm' 'test'"),
                worker.lastRequest.command());

        StudentProject project = new StudentProject();
        project.setProjectId(83);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService terminalService = new ProjectTerminalService(worker, properties);
        ProjectTerminalService.TerminalSession session = terminalService.create(1, project, "WSL managed", "frontend");

        ProjectTerminalService.TerminalRunResult terminal = terminalService.run(
                session, project, "npm install && npm run build", "", false, 240);

        assertEquals(0, terminal.exitCode(), terminal::output);
        assertTrue(terminal.execution() != null && "bash".equals(terminal.execution().shell()), terminal::output);
        assertEquals(List.of("/bin/bash", "--noprofile", "--norc", "-lc", "npm install && npm run build"),
                worker.lastRequest.command());
        assertTrue(terminal.output().contains("frontend build passed"), terminal::output);
    }

    private void deleteRecursively(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private JsonObject shellArgs(String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        args.addProperty("description", "WSL shell fixture validation");
        args.addProperty("timeout", 120_000);
        return args;
    }

    private void copyFixtureIntoWorkspace() throws Exception {
        Path source = Path.of("..", "scripts", "acceptance", "fixtures", "opencode-shell-fixture")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), () -> "fixture is missing: " + source);
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path sourcePath : entries.toList()) {
                Path target = workspace.resolve(source.relativize(sourcePath).toString());
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(sourcePath, target);
                }
            }
        }
    }

    private static final class RecordingWslSandboxWorker extends WslSandboxWorker {
        private WorkerRunSpec lastRun;
        private ProcessExecutionRequest lastRequest;

        private RecordingWslSandboxWorker(LocalProcessExecutor executor, String distribution) {
            super(executor, distribution);
        }

        @Override
        public ProcessExecutionResult execute(
                WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken cancellationToken) {
            this.lastRun = run;
            this.lastRequest = request;
            return super.execute(run, request, cancellationToken);
        }
    }

    private static final class ControlledCancellationToken implements CancellationToken {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public boolean isCancellationRequested() {
            return requested.get();
        }

        @Override
        public Registration onCancellation(Runnable listener) {
            listeners.add(listener);
            if (requested.get()) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private void cancel() {
            if (requested.compareAndSet(false, true)) {
                listeners.forEach(Runnable::run);
            }
        }
    }
}
