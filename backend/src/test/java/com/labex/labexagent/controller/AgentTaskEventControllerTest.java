package com.labex.labexagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.common.Result;
import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.CommandAuditEvent;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandAuditService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentTaskEventSubscriptionService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.AgentRunMessageService;
import com.labex.labexagent.service.AgentTaskService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentTaskEventControllerTest {

    @Test
    void exposesOnlyTheRecoverableActiveTaskMetadataForTheOwnedConversation() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setConversationId("conversation-71");
        task.setSessionId("session-71");
        task.setMode("build");
        task.setStatus("running");
        task.setCurrentStep("Testing");
        task.setSummary("Running tests");
        task.setLastEventSequence(42L);
        when(tasks.findLatestActiveTask(7, 12, "conversation-71")).thenReturn(task);
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStatus("consumed");
        approval.setDisplayCommand("mvn test");
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        CommandAuditService audit = mock(CommandAuditService.class);
        CommandAuditEvent execution = new CommandAuditEvent();
        execution.setExecutionStatus("failed");
        execution.setExitCode(1);
        execution.setDurationMs(125L);
        when(audit.findLatestExecutionOutcome("approval-71")).thenReturn(execution);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.publicHistory(71L)).thenReturn(java.util.List.of(Map.of("partKey", "tool:call-1")));
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);
        when(runMessages.publicHistory(71L)).thenReturn(java.util.List.of(Map.of("messageKey", "assistant:turn:1")));
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        when(compactions.publicHistory(71L)).thenReturn(java.util.List.of(Map.of(
                "compactionEpoch", 1L, "status", "completed")));
        AgentTaskEventController controller = new AgentTaskEventController(tasks,
                mock(AgentTaskEventSubscriptionService.class), approvals, audit, null, parts, runMessages);
        controller.setCompactionService(compactions);

        Result<Map<String, Object>> result = controller.activeTask(12, "conversation-71", authentication(7));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("taskId", 71L)
                .containsEntry("sessionId", "session-71")
                .containsEntry("lastEventSequence", 42L)
                .containsEntry("compactions", java.util.List.of(Map.of(
                        "compactionEpoch", 1L, "status", "completed")))
                .doesNotContainKeys("requestPayload", "userMessage");
        @SuppressWarnings("unchecked")
        Map<String, Object> approvalData = (Map<String, Object>) result.getData().get("commandApproval");
        assertThat(approvalData.get("approvalId")).isEqualTo("approval-71");
        assertThat(approvalData.get("status")).isEqualTo("consumed");
        assertThat(approvalData.get("executionStatus")).isEqualTo("failed");
        assertThat(approvalData.get("exitCode")).isEqualTo(1);
        assertThat(approvalData.get("durationMs")).isEqualTo(125L);
        assertThat(approvalData).doesNotContainKey("canonicalCommand");
    }

    @Test
    void exposesTerminalTaskProjectionByOwnedTaskId() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTask task = new AgentTask();
        task.setTaskId(81L);
        task.setConversationId("conversation-81");
        task.setSessionId("session-81");
        task.setMode("build");
        task.setStatus("completed");
        task.setLastEventSequence(88L);
        when(tasks.getOwnedTask(7, 12, 81L)).thenReturn(task);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        when(compactions.publicHistory(81L)).thenReturn(java.util.List.of(Map.of(
                "compactionEpoch", 2L, "status", "completed")));
        AgentTaskEventController controller = new AgentTaskEventController(tasks,
                mock(AgentTaskEventSubscriptionService.class), mock(CommandApprovalService.class),
                mock(CommandAuditService.class));
        controller.setCompactionService(compactions);

        Result<Map<String, Object>> result = controller.task(12, 81L, authentication(7));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("taskId", 81L)
                .containsEntry("status", "completed")
                .containsEntry("compactions", java.util.List.of(Map.of(
                        "compactionEpoch", 2L, "status", "completed")));
    }

    @Test
    void subscribesWithTheLastEventHeaderAsTheDurableReplayCursor() {
        AgentTaskEventSubscriptionService subscriptions = mock(AgentTaskEventSubscriptionService.class);
        SseEmitter returned = new SseEmitter(0L);
        when(subscriptions.subscribe(eq(7), eq(12), eq(71L), eq(42L), any(SseEmitter.class))).thenReturn(returned);
        AgentTaskEventController controller = new AgentTaskEventController(mock(AgentTaskService.class), subscriptions,
                mock(CommandApprovalService.class), mock(CommandAuditService.class));

        SseEmitter result = controller.subscribe(12, 71L, "42", null, authentication(7));

        assertThat(result).isSameAs(returned);
        verify(subscriptions).subscribe(eq(7), eq(12), eq(71L), eq(42L), any(SseEmitter.class));
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}
