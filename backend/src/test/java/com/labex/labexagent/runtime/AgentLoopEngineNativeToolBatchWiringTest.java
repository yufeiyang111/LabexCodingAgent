package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.tool.FileContentFingerprint;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.run.ExecutionFence;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentLoopEngineNativeToolBatchWiringTest {
    @TempDir
    Path workspace;

    @Test
    void nativeRuntimeRoutesStructuredToolBatchesThroughTheDurableBatchExecutor() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("LabexNativeToolBatchExecutor nativeToolBatchExecutor"));
        assertTrue(source.contains("void setNativeToolBatchExecutor"));
        assertTrue(source.contains("processLabexNativeToolBatch("));
        assertTrue(source.contains("AgentRuntimeProfile.LABEX_NATIVE"));
    }

    @Test
    void verifiedNativeMutationProjectsTheDurableCompletionReadinessEvent() throws Exception {
        AgentCompletionReadinessService readiness = mock(AgentCompletionReadinessService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(41L);
        event.setEventType("COMPLETION_READY");
        event.setPayload("{\"reasonCode\":\"verified_workspace_change\"}");
        when(readiness.signalIfReady(any(), eq(7L), eq(4L), eq(9), eq(3), eq(true))).thenReturn(
                new AgentCompletionReadinessService.Signal(
                        AgentCompletionReadinessService.SignalStatus.SIGNALED,
                        "verified_workspace_change", "evidence-1", null, event, Map.of(), "directive"));

        AgentLoopEngine engine = emptyEngine();
        engine.setCompletionReadinessService(readiness);
        AgentContext context = new AgentContext("session-7", 9, null, "conversation-7", 7L,
                Files.createTempDirectory("completion-readiness-context"), List.of(), 0);
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        context.setExecutionEpoch(4L);
        context.setExecutionFence(fence);
        context.applyExecutionProgressProjection("verifying", 1, 1, false, java.util.Set.of("read_file"), java.util.Set.of());
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setStudentId(9);
        task.setProjectId(3);
        SseEmitter emitter = mock(SseEmitter.class);
        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        Path runLog = Files.createTempFile("completion-readiness", ".md");

        Method method = AgentLoopEngine.class.getDeclaredMethod("maybeSignalCompletionReadiness",
                AgentSsePublisher.class, com.labex.entity.AgentConversation.class,
                AgentTask.class, AgentContext.class, Path.class, int.class);
        method.setAccessible(true);
        method.invoke(engine, publisher, null, task, context, runLog, 3);

        verify(readiness).signalIfReady(fence, 7L, 4L, 9, 3, true);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertTrue(Files.readString(runLog).contains("Completion readiness persisted after iteration 3"));
    }

    @Test
    void unverifiedWorkspaceMutationDoesNotIssueCompletionReadinessDirective() throws Exception {
        AgentCompletionReadinessService readiness = mock(AgentCompletionReadinessService.class);
        AgentLoopEngine engine = emptyEngine();
        engine.setCompletionReadinessService(readiness);
        AgentContext context = new AgentContext("session-7", 9, null, "conversation-7", 7L,
                Files.createTempDirectory("completion-readiness-mismatch"), List.of(), 0);
        context.setExecutionEpoch(4L);
        context.setExecutionFence(new ExecutionFence(7L, "instance-a", 4L));
        context.applyExecutionProgressProjection("repair", 1, 0, true, java.util.Set.of(),
                java.util.Set.of("skills/SKILL.md"));
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setStudentId(9);
        task.setProjectId(3);

        Method method = AgentLoopEngine.class.getDeclaredMethod("maybeSignalCompletionReadiness",
                AgentSsePublisher.class, com.labex.entity.AgentConversation.class,
                AgentTask.class, AgentContext.class, Path.class, int.class);
        method.setAccessible(true);
        method.invoke(engine, null, null, task, context, Files.createTempFile("completion-readiness", ".md"), 3);

        verifyNoInteractions(readiness);
    }

    @Test
    void shellSnapshotChangesProjectServerVerifiedWorkspaceFacts() throws Exception {
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        project.setWorkspacePath(workspace.toString());
        AgentContext context = new AgentContext("session-7", 9, project, "conversation-7", 7L,
                workspace, List.of(), 0);
        context.setExecutionEpoch(4L);
        PendingChange deleted = new PendingChange("change-71", 9, 3, "conversation-7", 7L, 11L,
                "skills/SKILL.md", "delete", "skill instructions\n", "",
                "diff --git a/skills/SKILL.md b/skills/SKILL.md", "applied");
        ToolResult result = ToolResult.ok("removed skill");

        Method method = AgentLoopEngine.class.getDeclaredMethod("attachSnapshotChanges",
                AgentContext.class, Path.class, ToolResult.class, List.class);
        method.setAccessible(true);
        method.invoke(emptyEngine(), context, workspace, result, List.of(deleted));

        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) result.durableResultMetadata().get("workspaceMutation");
        String beforeContent = "skill instructions\n";
        long beforeBytes = beforeContent.getBytes(StandardCharsets.UTF_8).length;
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.durableResultMetadata()
                .get("workspaceVerification");
        org.assertj.core.api.Assertions.assertThat(verification)
                .containsEntry("state", "verified")
                .containsEntry("targets", List.of(Map.of(
                        "path", "skills/SKILL.md",
                        "expectedState", "absent",
                        "observedState", "absent")));
        assertTrue(result.getWorkspaceIdentity().containsKey("operationFingerprint"));
        org.assertj.core.api.Assertions.assertThat(result.getWorkspaceIdentity())
                .containsEntry("taskId", 7L)
                .containsEntry("executionEpoch", 4L)
                .containsEntry("relativePaths", List.of("skills/SKILL.md"));
        org.assertj.core.api.Assertions.assertThat(mutation)
                .containsEntry("state", "applied")
                .containsEntry("changeIds", List.of("change-71"))
                .containsEntry("targets", List.of(Map.of(
                        "path", "skills/SKILL.md",
                        "operation", "delete",
                        "changeId", "change-71",
                        "before", Map.of(
                                "state", "present",
                                "sha256", FileContentFingerprint.sha256(beforeContent),
                                "bytes", beforeBytes),
                        "after", Map.of("state", "absent", "verified", true))));
        org.assertj.core.api.Assertions.assertThat(result.durableResultMetadata().toString())
                .doesNotContain(workspace.toAbsolutePath().normalize().toString());
    }

    private AgentLoopEngine emptyEngine() {
        return new AgentLoopEngine(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
