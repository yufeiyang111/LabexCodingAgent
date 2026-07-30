package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.network.NetworkAccessService;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AgentApprovedCommandExecutorTest {

    @TempDir
    Path workspace;

    @Test
    void executesOnlyConsumedAgentApprovalAsDirectArgv() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        AgentApprovedCommandExecutor executor = new AgentApprovedCommandExecutor(worker);

        executor.execute(consumedApproval(), project());

        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command()).containsExactly("npm", "test");
        assertThat(request.getValue().workingDirectory()).isEqualTo(workspace.toRealPath());
    }

    @Test
    void consumesNetworkGrantBeforeEnablingWorkerNetwork() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        when(network.consumeGrant(7, 12, 71L, "npm test")).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        AgentApprovedCommandExecutor executor = new AgentApprovedCommandExecutor(worker, network);
        CommandApproval approval = consumedApproval();
        approval.setCommandOptions("timeout=60;longRunning=false;network=true");
        approval.setStudentId(7);
        approval.setProjectId(12);

        executor.execute(approval, project());

        ArgumentCaptor<WorkerRunSpec> run = ArgumentCaptor.forClass(WorkerRunSpec.class);
        verify(worker).execute(run.capture(), any(), any());
        assertThat(run.getValue().policy().networkEnabled()).isTrue();
        verify(network).consumeGrant(7, 12, 71L, "npm test");
    }

    @Test
    void rejectsUnconsumedApprovalBeforeWorkerExecution() {
        SandboxWorker worker = mock(SandboxWorker.class);
        AgentApprovedCommandExecutor executor = new AgentApprovedCommandExecutor(worker);
        CommandApproval approval = consumedApproval();
        approval.setStatus("approved");

        assertThatThrownBy(() -> executor.execute(approval, project()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(worker);
    }

    private CommandApproval consumedApproval() {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-1");
        approval.setTaskId(71L);
        approval.setSource("agent_shell");
        approval.setStatus("consumed");
        approval.setCanonicalCommand("npm test");
        approval.setWorkingDirectory(".");
        approval.setCommandOptions("timeout=60;longRunning=false");
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(10));
        return approval;
    }

    private StudentProject project() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}
