package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.workspace.WorkspaceOperationIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentToolCallJournalServiceTest {

    @Test
    void toolPartIsTheOnlyJournalAuthority() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/run/AgentToolCallJournalService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("AgentRunArtifactService"));
        assertFalse(source.contains("\"tool_call_state\""));
        assertFalse(source.contains("recordDeterministic"));
        assertFalse(source.contains("latestForTask"));
        assertFalse(source.contains("artifactService"));
    }

    @Test
    void requiresTheDurablePartDependencyAtConstructionTime() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);

        assertThrows(NullPointerException.class,
                () -> new AgentToolCallJournalService(lifecycle, null));
    }

    @Test
    void partPersistenceFailureStopsEventPublication() {
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(fence), eq(7L), eq("call-fail"), eq("running"), eq("run_tests"), any(), eq(2), eq("")))
                .thenThrow(new IllegalStateException("part store unavailable"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        assertThrows(IllegalStateException.class,
                () -> journal.running(fence, 7L, "call-fail", "run_tests", Map.of("command", "mvn test"), 2));

        verify(lifecycle, never()).appendEvent(any(ExecutionFence.class), any(), any(), any(), any());
    }

    @Test
    void persistsTheToolPartAndPublishesAnIdempotentTaskEvent() {
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(fence), eq(7L), eq("call-1"), eq("running"), eq("run_tests"), any(), eq(2), eq("")))
                .thenReturn(part(191L, "tool:call-1", "call-1", "run_tests"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.running(fence, 7L, "call-1", "run_tests", Map.of("command", "mvn test"), 2);

        verify(parts).upsertToolCall(eq(fence), eq(7L), eq("call-1"), eq("running"), eq("run_tests"), any(), eq(2), eq(""));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(fence), eq(7L), eq("TOOL_CALL_STATE"), payload.capture(),
                eq("tool-call-state-part-191-running"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event).containsEntry("partId", 191L)
                .containsEntry("partKey", "tool:call-1")
                .doesNotContainKey("artifactId");
    }

    @Test
    void persistsStructuredWorkspaceIdentityWithTheCompletedToolPart() {
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(fence), eq(7L), eq("call-shell"), eq("completed"), eq("shell"),
                any(), eq(2), org.mockito.ArgumentMatchers.startsWith("exit=0"), any()))
                .thenReturn(part(192L, "tool:call-shell", "call-shell", "shell"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);
        ToolResult result = ToolResult.fromObservedProcessExecution(
                new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 5, "ok", false),
                "direct", "frontend", "artifacts/shell.log")
                .withWorkspaceIdentity(new WorkspaceOperationIdentity(
                        1, 7, 12, "conversation-1", 7L, 4L, "workspace-hash", "frontend", List.of(), ""))
                .withWorkspaceVerification(Map.of("state", "verified", "targets", List.of(Map.of(
                        "path", "src/App.vue", "expectedState", "present", "observedState", "present"))));

        journal.completed(fence, 7L, "call-shell", "shell", Map.of("command", "npm test"), 2, result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(parts).upsertToolCall(eq(fence), eq(7L), eq("call-shell"), eq("completed"), eq("shell"),
                any(), eq(2), org.mockito.ArgumentMatchers.startsWith("exit=0"), metadata.capture());
        assertThat(metadata.getValue())
                .containsKey("workspaceIdentity")
                .containsKey("workspaceVerification")
                .containsKey("execution");
        @SuppressWarnings("unchecked")
        Map<String, Object> workspaceIdentity = (Map<String, Object>) metadata.getValue().get("workspaceIdentity");
        assertThat(workspaceIdentity)
                .containsEntry("taskId", 7L)
                .containsEntry("executionEpoch", 4L)
                .containsEntry("workingDirectory", "frontend");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(fence), eq(7L), eq("TOOL_CALL_STATE"), payload.capture(),
                eq("tool-call-state-part-192-completed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event).containsKey("metadata");
    }
    @Test
    void completesAnExistingToolCallDirectlyFromTheDurablePart() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunPart existing = part(191L, "tool:call-1", "call-1", "run_tests");
        existing.setInputJson("{\"command\":\"mvn test\"}");
        existing.setStatus("completed");
        existing.setSequenceNumber(2L);
        when(parts.resolveExistingToolCall(7L, "call-1", "completed", "exit=0"))
                .thenReturn(existing);
        when(parts.projectToolCall(existing)).thenReturn(Map.of(
                "partId", 191L,
                "partKey", "tool:call-1",
                "toolCallId", "call-1",
                "tool", "run_tests",
                "arguments", Map.of("command", "mvn test"),
                "status", "completed",
                "iteration", 2L,
                "detail", "exit=0"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.completedExisting(7L, "call-1", "exit=0");

        verify(parts).resolveExistingToolCall(7L, "call-1", "completed", "exit=0");
        verify(parts, never()).upsertToolCall(any(ExecutionFence.class), any(), any(), any(), any(), any(), any(Integer.class), any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), payload.capture(),
                eq("tool-call-state-part-191-completed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event.get("arguments")).isEqualTo(Map.of("command", "mvn test"));
    }

    @Test
    void externallyResolvedToolResultPreservesStructuredWorkspaceEvidence() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunPart existing = part(191L, "tool:call-1", "call-1", "shell");
        existing.setInputJson("{\"command\":\"rm -rf skills\"}");
        existing.setStatus("completed");
        existing.setSequenceNumber(2L);
        WorkspaceOperationIdentity identity = new WorkspaceOperationIdentity(
                1, 7, 12, "conversation-1", 7L, 4L, "workspace-fingerprint", ".",
                List.of("skills/SKILL.md"), "operation-fingerprint");
        ToolResult result = ToolResult.ok("status=completed\nexit=0")
                .withWorkspaceChangeEvidence(identity, List.of("change-1"))
                .withWorkspaceVerification(Map.of(
                        "state", "verified",
                        "targets", List.of(Map.of(
                                "path", "skills/SKILL.md",
                                "expectedState", "absent",
                                "observedState", "absent"))));
        when(parts.resolveExistingToolCall(eq(7L), eq("call-1"), eq("completed"),
                eq("status=completed\nexit=0"), any())).thenReturn(existing);
        when(parts.projectToolCall(existing)).thenReturn(Map.of(
                "partId", 191L,
                "partKey", "tool:call-1",
                "toolCallId", "call-1",
                "tool", "shell",
                "arguments", Map.of("command", "rm -rf skills"),
                "status", "completed",
                "iteration", 2L,
                "detail", "status=completed\nexit=0"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.completedExisting(7L, "call-1", result);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(parts).resolveExistingToolCall(eq(7L), eq("call-1"), eq("completed"),
                eq("status=completed\nexit=0"), metadata.capture());
        assertThat(metadata.getValue())
                .containsKey("workspaceIdentity")
                .containsKey("workspaceMutation")
                .containsKey("workspaceVerification");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), payload.capture(),
                eq("tool-call-state-part-191-completed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event).containsKey("metadata");
    }

    @Test
    void waitingInteractionsRemainRecoverableToolPartStates() {
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(fence), eq(7L), eq("call-question"), eq("waiting_user"), eq("question"), any(), eq(1), eq("Waiting for an answer")))
                .thenReturn(part(193L, "tool:call-question", "call-question", "question"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.waitingUser(fence, 7L, "call-question", "question", Map.of("question", "Continue?"), 1,
                "request-question", "Waiting for an answer", Map.of("question", "Continue?"));

        verify(parts).upsertToolCall(eq(fence), eq(7L), eq("call-question"), eq("waiting_user"), eq("question"),
                any(), eq(1), eq("Waiting for an answer"));
        verify(lifecycle).appendEvent(eq(fence), eq(7L), eq("TOOL_CALL_STATE"), any(),
                eq("tool-call-state-part-193-waiting_user"));
    }

    @Test
    void staleFenceRejectsWithZeroDurableWrites() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunPartMapper partMapper = mock(AgentRunPartMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunPartService parts = new AgentRunPartService(partMapper, tasks, messages, leases);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);

        assertThatThrownBy(() -> journal.running(fence, 7L, "call-1", "run_tests",
                Map.of("command", "mvn test"), 2))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);

        verify(partMapper, never()).selectList(any());
        verify(partMapper, never()).insert(any());
        verify(partMapper, never()).updateById(any());
        verifyNoInteractions(messages);
        verify(lifecycle, never()).appendEvent(any(ExecutionFence.class), any(), any(), any(), any());
    }

    @Test
    void activeFencePersistsTheToolPartAndPublishesTheEvent() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunPartMapper partMapper = mock(AgentRunPartMapper.class);
        when(partMapper.selectList(any())).thenReturn(null);
        when(partMapper.selectOne(any())).thenReturn(null);
        when(partMapper.insert(any(AgentRunPart.class))).thenReturn(1);
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setConversationId("conversation-1");
        task.setStudentId(7);
        task.setProjectId(12);
        when(tasks.selectById(7L)).thenReturn(task);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(77L);
        when(messages.upsertAssistantTurn(eq(7L), eq(2L), any())).thenReturn(message);
        AgentRunPartService parts = new AgentRunPartService(partMapper, tasks, messages, leases);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.appendEvent(any(ExecutionFence.class), eq(7L), eq("TOOL_CALL_STATE"), any(), any()))
                .thenReturn(null);
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);

        journal.running(fence, 7L, "call-1", "run_tests", Map.of("command", "mvn test"), 2);

        ArgumentCaptor<AgentRunPart> partCaptor = ArgumentCaptor.forClass(AgentRunPart.class);
        verify(partMapper).insert(partCaptor.capture());
        assertThat(partCaptor.getValue().getToolCallId()).isEqualTo("call-1");
        assertThat(partCaptor.getValue().getStatus()).isEqualTo("running");
        verify(lifecycle).appendEvent(eq(fence), eq(7L), eq("TOOL_CALL_STATE"), any(),
                any());
    }

    @Test
    void interruptedExecutionPersistsTheDurableInterruptedPartState() {
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(fence), eq(7L), eq("call-cancel"), eq("interrupted"), eq("shell"), any(), eq(1), eq("exit=130")))
                .thenReturn(part(194L, "tool:call-cancel", "call-cancel", "shell"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.interrupted(fence, 7L, "call-cancel", "shell", Map.of("command", "npm run dev"), 1, "exit=130");

        verify(parts).upsertToolCall(eq(fence), eq(7L), eq("call-cancel"), eq("interrupted"), eq("shell"),
                any(), eq(1), eq("exit=130"));
        verify(lifecycle).appendEvent(eq(fence), eq(7L), eq("TOOL_CALL_STATE"), any(),
                eq("tool-call-state-part-194-interrupted"));
    }

    private AgentRunPart part(long id, String partKey, String toolCallId, String toolName) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(id);
        part.setPartKey(partKey);
        part.setToolCallId(toolCallId);
        part.setToolName(toolName);
        return part;
    }
}
