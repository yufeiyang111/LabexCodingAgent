package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.commandsecurity.VerificationStrategy;
import com.labex.labexagent.run.AgentRecoveryProperties;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.AgentVerificationMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class RunTestsToolTest {

    @TempDir
    Path workspace;

    @Test
    void keepsANonZeroTestExitAsAFailedVerification() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.FAILED, 1, 10, "test failure", false));
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"node -e 'process.exit(1)'\"}}");
        RunTestsTool tool = new RunTestsTool(worker);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        JsonObject args = new JsonObject();
        args.addProperty("strategy", "test");

        ToolResult result = tool.execute(context(), args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).contains("exit=1", "status=failed");
    }
    @Test
    void exposesNoModelControlledCommandProperty() {
        RunTestsTool tool = new RunTestsTool(mock(SandboxWorker.class));

        assertThat(tool.definition().getInputSchema().get("properties"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("command");
    }

    @Test
    void executesDetectedMavenTestsThroughTheWorkerShellDescriptor() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");

        RunTestsTool tool = new RunTestsTool(worker);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        ToolResult result = tool.execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command())
                .containsExactly("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "& 'mvn' 'test'");
        assertThat(request.getValue().workingDirectory()).isEqualTo(workspace);
        assertThat(result.getContent()).contains("shell=powershell", "workdir=.");
    }
    @Test
    void acceptanceProfileCanExecuteApprovalRequiredVerificationCommand() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"node -e 'process.exit(0)'\"}}");

        RunTestsTool tool = new RunTestsTool(worker);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);

        JsonObject args = new JsonObject();
        args.addProperty("strategy", "test");
        ToolResult result = tool.execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        verify(worker).execute(any(), any(), any());
    }

    @Test
    void environmentRecoveryIgnoresModelStrategyOverrideAndRepeatsConfiguredVerification() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        AgentContext context = context();
        context.setEnvironmentRecovery(true);
        JsonObject args = new JsonObject();
        args.addProperty("strategy", "compile");

        RunTestsTool tool = new RunTestsTool(worker);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        ToolResult result = tool.execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command())
                .containsExactly("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "& 'mvn' 'test'");
    }

    @Test
    void usesConfiguredFallbackStrategyWhenManualPrimaryHasNoCommand() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        AgentRecoveryProperties properties = new AgentRecoveryProperties();
        properties.setVerificationStrategy(VerificationStrategy.MANUAL);
        properties.setFallbackVerificationStrategy(VerificationStrategy.COMPILE);

        RunTestsTool tool = new RunTestsTool(worker, null, null, properties);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        ToolResult result = tool.execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command())
                .containsExactly("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "& 'mvn' 'compile'");
    }
    @Test
    void rejectsUnsupportedWorkspaceBeforeWorkerExecution() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);

        ToolResult result = new RunTestsTool(worker).execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).contains("无法识别项目类型");
        verify(worker, never()).execute(any(), any(), any());
    }

    @Test
    void doesNotInventNpmTestWhenPackageHasNoVerificationScript() throws Exception {
        // package.json \u6ca1\u6709\u9a8c\u8bc1\u811a\u672c\u65f6\u4e0d\u80fd\u4f2a\u9020 npm test\u3002
        SandboxWorker worker = mock(SandboxWorker.class);
        Files.writeString(workspace.resolve("package.json"), "{}");
        JsonObject args = new JsonObject();
        args.addProperty("timeout_seconds", 9999);

        ToolResult result = new RunTestsTool(worker).execute(context(), args);

        assertThat(result.isSuccess()).isFalse();
        verify(worker, never()).execute(any(), any(), any());
    }

    @Test
    void selectsChangedFrontendModuleAndUsesColdCacheBuildTimeout() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project />");
        Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(workspace.resolve("frontend/package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}");
        AgentContext context = context();
        context.applyExecutionProgressProjection(
                "implement", 1, 0, true, java.util.Set.of(), java.util.Set.of("frontend/src/App.vue"));
        RunTestsTool tool = new RunTestsTool(worker);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        JsonObject args = new JsonObject();
        args.addProperty("strategy", "build");

        ToolResult result = tool.execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().workingDirectory()).isEqualTo(workspace.resolve("frontend"));
        assertThat(request.getValue().command()).containsExactly(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "& 'npm' 'run' 'build'");
        assertThat(request.getValue().timeout()).isEqualTo(java.time.Duration.ofSeconds(240));
    }

    @Test
    void usesBashPayloadWhenTheWorkerDeclaresLinuxShell() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.shellDescriptor(any())).thenReturn(
                com.labex.labexagent.execution.WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true));
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");

        RunTestsTool tool = new RunTestsTool(worker);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        ToolResult result = tool.execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command())
                .containsExactly("/bin/bash", "--noprofile", "--norc", "-lc", "'mvn' 'test'");
        assertThat(result.getContent()).contains("shell=bash", "workdir=.");
    }

    @Test
    void exposesSafeTargetPathButStillNoModelControlledCommand() {
        RunTestsTool tool = new RunTestsTool(mock(SandboxWorker.class));

        assertThat(tool.definition().getInputSchema().get("properties"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("target_path")
                .doesNotContainKey("command");
    }

    @Test
    void passesTheContextExecutionFenceToTheVerificationRecorder() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        AgentVerificationRecorder recorder = mock(AgentVerificationRecorder.class);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        RunTestsTool tool = new RunTestsTool(worker, recorder);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        ToolResult result = tool.execute(fencedContext(fence), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        verify(recorder).recordToolResult(eq(fence), eq(71L), eq(7), eq(12), any(), any(), any());
    }

    @Test
    void staleFenceFromTheVerificationRecorderSurfacesInsteadOfCompleting() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentVerificationMapper verificationMapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(verificationMapper, artifacts);
        recorder.setExecutionLeaseService(leases);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        RunTestsTool tool = new RunTestsTool(worker, recorder);
        ReflectionTestUtils.setField(tool, "acceptanceAutoApproveVerification", true);
        assertThatThrownBy(() -> tool.execute(fencedContext(fence), new JsonObject()))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
        verify(verificationMapper, never()).insert(any());
    }

    private AgentContext fencedContext(ExecutionFence fence) {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        AgentContext context = new AgentContext("session-1", 7, project, "conversation-1", 71L, workspace,
                new ArrayList<>(), 0);
        context.setExecutionFence(fence);
        return context;
    }

    private AgentContext context() {
        return new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), 0);
    }
}
