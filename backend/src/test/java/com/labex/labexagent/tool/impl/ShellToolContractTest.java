package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ShellToolContractTest {
    @TempDir
    Path workspace;

    @Test
    void exposesOnlyCanonicalShellArgumentsToTheModel() {
        ToolDefinition definition = new RunCommandTool(mock(SandboxWorker.class)).definition();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) definition.getInputSchema().get("properties");
        assertThat(properties).containsKeys("command", "workdir", "timeout", "description");
        assertThat(properties).doesNotContainKeys("working_directory", "workingDirectory", "cwd", "timeout_seconds", "network");
    }

    @Test
    void forwardsCompleteShellPayloadWithoutTokenizingIt() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(
                new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 7, "installed", false));
        Path frontend = Files.createDirectories(workspace.resolve("frontend"));
        AgentContext context = context();
        String command = "cd frontend&&printf \"hello world\" > \"build output.txt\" | cat && echo $HOME";
        JsonObject args = new JsonObject();
        args.addProperty("command", command);

        ToolResult result = new RunCommandTool(worker).execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command()).containsExactly("/bin/bash", "--noprofile", "--norc", "-lc", command);
        assertThat(request.getValue().workingDirectory()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(request.getValue().outputArtifactPath())
                .isEqualTo(workspace.resolve(".labex-agent/artifacts/task-1/shell-standalone.log")
                        .toAbsolutePath().normalize());
        assertThat(frontend).isDirectory();
    }

    @Test
    void acceptsWorkdirAndMillisecondTimeout() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(
                new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 3, "ok", false));
        Path frontend = Files.createDirectories(workspace.resolve("frontend"));
        JsonObject args = new JsonObject();
        args.addProperty("command", "printf ok");
        args.addProperty("workdir", "frontend");
        args.addProperty("timeout", 1234);

        ToolResult result = new RunCommandTool(worker).execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().workingDirectory()).isEqualTo(frontend.toAbsolutePath().normalize());
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofMillis(1234));
    }

    @Test
    void givesPythonShellCommandsATestSizedDefaultTimeout() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(
                new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 3, "ok", false));
        JsonObject args = new JsonObject();
        args.addProperty("command", "python -m pytest -q");

        ToolResult result = new RunCommandTool(worker).execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        // python 命令会先触发一次只读工具探测（10s 预算），随后才是真实命令（python 默认 180s）。
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        ProcessExecutionRequest call = request.getValue();
        assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(call.command())
                .containsExactly("/bin/bash", "--noprofile", "--norc", "-lc", "python -m pytest -q");
        assertThat(call.workingDirectory()).isEqualTo(workspace.toAbsolutePath().normalize());
    }

    @Test
    void treatsAnObservedNonZeroShellExitAsACompletedToolCall() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 1, 7, "no matching process", false));
        JsonObject args = new JsonObject();
        args.addProperty("command", "ps aux | grep [p]ython");

        ToolResult result = new RunCommandTool(worker).execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExecutionStatus()).isEqualTo("failed");
        assertThat(result.getExecutionExitCode()).isEqualTo(1);
        assertThat(result.getContent())
                .contains("execution=completed")
                .contains("outcome=non_zero_exit")
                .contains("exit=1");
    }
    @Test
    void preservesNonZeroExitMetadataForTheModel() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 17, 42, "compile failed", true));
        JsonObject args = new JsonObject();
        args.addProperty("command", "node -e \"process.exit(17)\"");

        ToolResult result = new RunCommandTool(worker).execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent())
                .contains("execution=completed")
                .contains("outcome=non_zero_exit")
                .contains("exit=17")
                .contains("status=failed")
                .contains("duration_ms=42")
                .contains("truncated=true")
                .contains("compile failed");
    }

    private AgentContext context() {
        return new AgentContext("session-shell-contract", 1, null, "conversation-shell-contract", 1L,
                workspace, new ArrayList<>(), 0);
    }
}
