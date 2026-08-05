package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.labex.entity.StudentProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCheckpointStoreTest {

    @TempDir
    Path workspace;

    @Test
    void readsV2ExecutionStateOnlyAsAnExplicitLegacyMigrationSeed() throws Exception {
        StudentProject project = project(workspace);
        AgentCheckpointStore store = new AgentCheckpointStore();
        Path checkpoint = store.checkpointPath(project, "conversation-a", 41L);
        Files.createDirectories(checkpoint.getParent());
        Files.writeString(checkpoint, """
                {
                  "version": 2,
                  "conversationId": "conversation-a",
                  "taskId": 41,
                  "status": "waiting_user",
                  "stage": "verify",
                  "writeCount": 2,
                  "verificationCount": 1,
                  "unverifiedChanges": true,
                  "trustedVerificationSources": ["run_tests"],
                  "unverifiedChangeTargets": ["src/Main.java"],
                  "note": "Need confirmation",
                  "lastTool": "question",
                  "lastResult": "pending",
                  "runLog": ".labex/agent-logs/run.md"
                }
                """);

        AgentCheckpointStore.Snapshot snapshot = store
                .loadLegacy(project, "conversation-a", 41L).orElseThrow();
        AgentCheckpointStore.LegacyExecutionSeed seed = snapshot.legacyExecutionSeed().orElseThrow();

        assertEquals(2, snapshot.version());
        assertEquals("verify", seed.stage());
        assertEquals(2, seed.writeCount());
        assertEquals(1, seed.verificationCount());
        assertTrue(seed.unverifiedChanges());
        assertEquals(Set.of("run_tests"), seed.trustedVerificationSources());
        assertEquals(Set.of("src/Main.java"), seed.unverifiedChangeTargets());
        assertEquals("question", seed.lastTool());
        assertEquals("pending", seed.lastResult());
        assertEquals(".labex/agent-logs/run.md", seed.runLogPath());
        assertEquals("Need confirmation", seed.resumeNote());
        assertTrue(snapshot.legacyPlanSeed().isEmpty());
        assertTrue(store.loadLegacy(project, "conversation-b", 41L).isEmpty());
        assertTrue(store.loadLegacy(project, "conversation-a", 42L).isEmpty());
    }

    @Test
    void readsAV1PlanAndExecutionStateOnlyAsOneTimeMigrationSeeds() throws Exception {
        StudentProject project = project(workspace);
        AgentCheckpointStore store = new AgentCheckpointStore();
        Path checkpoint = store.checkpointPath(project, "conversation-a", 41L);
        Files.createDirectories(checkpoint.getParent());
        Files.writeString(checkpoint, """
                {
                  "version": 1,
                  "conversationId": "conversation-a",
                  "taskId": 41,
                  "status": "waiting_user",
                  "stage": "implement",
                  "writeCount": 1,
                  "verificationCount": 0,
                  "unverifiedChanges": true,
                  "trustedVerificationSources": [],
                  "unverifiedChangeTargets": ["src/Main.java"],
                  "lastTool": "write_file",
                  "lastResult": "saved",
                  "plan": [
                    {"title":"Inspect","description":"Read state","completed":true},
                    {"title":"Resume","description":"Continue task","completed":false}
                  ],
                  "currentPlanIndex": 1
                }
                """);

        AgentCheckpointStore.Snapshot snapshot = store
                .loadLegacy(project, "conversation-a", 41L).orElseThrow();
        AgentCheckpointStore.LegacyPlanSeed plan = snapshot.legacyPlanSeed().orElseThrow();
        AgentCheckpointStore.LegacyExecutionSeed execution = snapshot.legacyExecutionSeed().orElseThrow();

        assertEquals(1, snapshot.version());
        assertEquals(1, plan.currentPlanIndex());
        assertEquals(2, plan.items().size());
        assertTrue(plan.items().get(0).isCompleted());
        assertFalse(plan.items().get(1).isCompleted());
        assertEquals("implement", execution.stage());
        assertEquals(1, execution.writeCount());
        assertEquals(Set.of("src/Main.java"), execution.unverifiedChangeTargets());
    }

    @Test
    void scansLegacyCheckpointInventoryWithoutTreatingInvalidJsonAsCovered() throws Exception {
        StudentProject project = project(workspace);
        AgentCheckpointStore store = new AgentCheckpointStore();
        Path valid = store.checkpointPath(project, "conversation-a", 41L);
        Files.createDirectories(valid.getParent());
        Files.writeString(valid, """
                {"version":2,"conversationId":"conversation-a","taskId":41,"stage":"verify"}
                """);
        Path invalid = valid.getParent().resolve("broken.json");
        Files.writeString(invalid, "{not-json");

        AgentCheckpointStore.LegacyInventory inventory = store.scanLegacySources(java.util.List.of(project));

        assertEquals(1, inventory.sources().size());
        assertEquals(41L, inventory.sources().get(0).taskId());
        assertEquals(1L, inventory.invalidSources());
        assertFalse(inventory.truncated());
    }

    @Test
    void exactLegacyCheckpointWithInvalidIdentityFailsClosedInsteadOfLookingAbsent() throws Exception {
        StudentProject project = project(workspace);
        AgentCheckpointStore store = new AgentCheckpointStore();
        Path checkpoint = store.checkpointPath(project, "conversation-a", 41L);
        Files.createDirectories(checkpoint.getParent());
        Files.writeString(checkpoint, """
                {"version":2,"conversationId":"conversation-b","taskId":41}
                """);

        assertThrows(IllegalStateException.class,
                () -> store.loadLegacy(project, "conversation-a", 41L));
    }

    @Test
    void inventoryRejectsCheckpointWhosePayloadDoesNotMatchItsCanonicalPath() throws Exception {
        StudentProject project = project(workspace);
        AgentCheckpointStore store = new AgentCheckpointStore();
        Path misplaced = store.checkpointPath(project, "conversation-a", 41L);
        Files.createDirectories(misplaced.getParent());
        Files.writeString(misplaced, """
                {"version":2,"conversationId":"conversation-b","taskId":42}
                """);

        AgentCheckpointStore.LegacyInventory inventory = store.scanLegacySources(java.util.List.of(project));

        assertTrue(inventory.sources().isEmpty());
        assertEquals(1L, inventory.invalidSources());
        assertFalse(inventory.truncated());
    }

    @Test
    void isReadOnlyAndDoesNotExposeANewCheckpointWritePath() {
        assertTrue(Arrays.stream(AgentCheckpointStore.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("save")));
        assertTrue(Arrays.stream(AgentCheckpointStore.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("renderForPrompt")));
    }

    private static StudentProject project(Path workspace) {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setProjectName("checkpoint-test");
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}