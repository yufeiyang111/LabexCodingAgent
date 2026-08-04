package com.labex.labexagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.common.Result;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.commandsecurity.AgentApprovedCommandExecutor;
import com.labex.labexagent.commandsecurity.CommandApprovalOrchestrator;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import com.labex.service.StudentProjectService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class StudentAgentControllerCommandApprovalTest {

    @Test
    void executesOnlyTheStoredConsumedAgentApproval() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = agentApproval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(executor.execute(approval, project)).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 12, "--token=secret result", false));
        StudentAgentController controller = controller(approvals, executor, projects, tasks);

        Result<Map<String, Object>> response = controller.executeCommandApproval(12, "approval-71", authentication(7));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).containsEntry("status", "completed");
        assertThat(String.valueOf(response.getData().get("output"))).doesNotContain("secret");
        verify(approvals).consume(any(CommandApprovalService.ConsumeRequest.class));
        verify(executor).execute(approval, project);
        verify(projects).refreshProjectMetadata(7, 12);
        verify(tasks).updateTask(eq(71L), eq("completed"), any(), any());
    }

    @Test
    void projectsCancelledApprovedCommandWithoutCallingItFailed() {
        CommandApprovalOrchestrator orchestrator = mock(CommandApprovalOrchestrator.class);
        CommandApproval approval = agentApproval("consumed");
        ProcessExecutionResult cancelled = new ProcessExecutionResult(
                ExecutionStatus.CANCELLED, null, 48L, "", false);
        when(orchestrator.execute(7, 12, "approval-71")).thenReturn(
                new CommandApprovalOrchestrator.ExecutionResult(true, approval, cancelled, "cancelled"));
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), mock(AgentConversationService.class), mock(AgentTaskService.class),
                mock(TokenTracker.class), mock(PermissionService.class), mock(AgentInteractionService.class),
                null, null, mock(CommandApprovalService.class), mock(AgentApprovedCommandExecutor.class),
                mock(StudentProjectService.class), orchestrator);

        Result<Map<String, Object>> response = controller.executeCommandApproval(
                12, "approval-71", authentication(7));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData())
                .containsEntry("status", "cancelled")
                .containsEntry("executionStatus", "cancelled")
                .containsEntry("resumeAgentLoop", false);
    }

    @Test
    void rejectsForeignOrUnconsumableApprovalWithoutExecutingWorker() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(null);
        StudentAgentController controller = controller(approvals, executor, projects, tasks);

        Result<Map<String, Object>> response = controller.executeCommandApproval(12, "approval-71", authentication(7));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).containsEntry("approvalUnavailable", true);
        verify(approvals, never()).consume(any());
        verify(executor, never()).execute(any(), any());
        verify(tasks, never()).updateTask(any(), any(), any(), any());
    }

    @Test
    void decisionRequestExposesOnlyPersistedPublicMetadata() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandApproval approval = agentApproval("pending");
        approval.setDisplayCommand("npm test --token=<redacted>");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.decide(7, 12, "approval-71", true, "decision-71")).thenReturn(approval);
        StudentAgentController controller = controller(approvals, mock(AgentApprovedCommandExecutor.class),
                mock(StudentProjectService.class), mock(AgentTaskService.class));
        StudentAgentController.CommandApprovalDecisionRequest request =
                new StudentAgentController.CommandApprovalDecisionRequest();
        request.setAction("approve");
        request.setDecisionIdempotencyKey("decision-71");

        Result<Map<String, Object>> response = controller.decideCommandApproval(12, "approval-71", request, authentication(7));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData())
                .containsEntry("approvalId", "approval-71")
                .containsEntry("displayCommand", "npm test --token=<redacted>")
                .doesNotContainKey("canonicalCommand")
                .doesNotContainKey("commandDigest");
    }

    private StudentAgentController controller(CommandApprovalService approvals,
                                              AgentApprovedCommandExecutor executor,
                                              StudentProjectService projects,
                                              AgentTaskService tasks) {
        return new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), mock(AgentConversationService.class), tasks,
                mock(TokenTracker.class), mock(PermissionService.class), mock(AgentInteractionService.class),
                null, null, approvals, executor, projects, null);
    }

    private CommandApproval agentApproval(String status) {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setTaskId(71L);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_shell");
        approval.setInvocationId("invoke-71");
        approval.setToolCallId("tool-71");
        approval.setCommandDigest("digest-71");
        approval.setCanonicalCommand("npm test --token=secret");
        approval.setDisplayCommand("npm test --token=<redacted>");
        approval.setWorkingDirectory(".");
        approval.setShell("direct");
        approval.setCommandOptions("timeout=60;longRunning=false");
        approval.setClassification("REQUIRE_APPROVAL");
        approval.setPolicyVersion("policy-v1");
        approval.setStatus(status);
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(10));
        return approval;
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}
