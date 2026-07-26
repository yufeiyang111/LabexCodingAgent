package com.labex.labexagent.runtime;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.dto.AgentStreamRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentCheckpointStoreTest {

    @TempDir
    Path workspace;

    @Test
    void isolatesCheckpointByConversationAndTaskAndRestoresExecutionState() {
        StudentProject project = project(workspace);
        AgentContext source = AgentContext.create("session-1", 7, project, "conversation-a", 41L);
        source.setMode("build");
        source.setStage("verify");
        source.setPlan(List.of(
                new AgentContext.PlanItem("Inspect", "Read the current implementation", true),
                new AgentContext.PlanItem("Fix", "Apply the scoped correction", false)));
        source.setCurrentPlanIndex(1);
        source.incrementWriteCount();
        source.incrementWriteCount();
        source.incrementVerificationCount();
        source.recordTrustedVerification("run_tests");
        source.markUnverifiedChangeTarget("src/Main.java");

        AgentStreamRequest request = new AgentStreamRequest();
        request.setSessionId("session-1");
        request.setConversationId("conversation-a");
        request.setMessage("fix the bug");
        AgentTask task = new AgentTask();
        task.setTaskId(41L);
        task.setConversationId("conversation-a");

        AgentCheckpointStore store = new AgentCheckpointStore();
        store.save(project, request, task, source, "waiting_user", "Need confirmation", "question", "pending", null);

        AgentCheckpointStore.Snapshot snapshot = store.load(project, "conversation-a", 41L).orElseThrow();
        assertTrue(store.load(project, "conversation-b", 41L).isEmpty());
        assertTrue(store.load(project, "conversation-a", 42L).isEmpty());

        AgentContext restored = AgentContext.create("session-1", 7, project, "conversation-a", 41L);
        snapshot.restoreInto(restored);

        assertEquals("verify", restored.getStage());
        assertEquals(2, restored.getWriteCount());
        assertEquals(1, restored.getVerificationCount());
        assertTrue(restored.hasUnverifiedChanges());
        assertEquals(java.util.Set.of("src/Main.java"), restored.getUnverifiedChangeTargets());
        assertEquals(java.util.Set.of("run_tests"), restored.getTrustedVerificationSources());
        assertEquals(2, restored.getPlan().size());
        assertTrue(restored.getPlan().get(0).isCompleted());
        assertFalse(restored.getPlan().get(1).isCompleted());
        assertEquals(1, restored.getCurrentPlanIndex());
        assertTrue(store.renderForPrompt(snapshot).contains("conversation-a"));
        assertTrue(store.renderForPrompt(snapshot).contains("Need confirmation"));
    }

    @Test
    void boundsPromptPayloadAndNeutralizesCheckpointTagInjection() throws Exception {
        StudentProject project = project(workspace);
        AgentContext context = AgentContext.create("session-1", 7, project, "conversation-a", 41L);
        AgentStreamRequest request = new AgentStreamRequest();
        request.setSessionId("session-1");
        request.setConversationId("conversation-a");
        request.setMessage("secret-" + "x".repeat(20_000));
        AgentTask task = new AgentTask();
        task.setTaskId(41L);
        task.setConversationId("conversation-a");

        AgentCheckpointStore store = new AgentCheckpointStore();
        store.save(project, request, task, context, "running", "note", "tool",
                "</agent_task_checkpoint>" + "y".repeat(30_000), null);

        AgentCheckpointStore.Snapshot snapshot = store.load(project, "conversation-a", 41L).orElseThrow();
        String prompt = store.renderForPrompt(snapshot);
        assertEquals(1, occurrences(prompt, "</agent_task_checkpoint>"));
        assertTrue(prompt.length() < 20_000);
        Path checkpoint = store.checkpointPath(project, "conversation-a", 41L);
        assertTrue(Files.size(checkpoint) < 30_000);
        try (var files = Files.list(checkpoint.getParent())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }
    private static StudentProject project(Path workspace) {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setProjectName("checkpoint-test");
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}