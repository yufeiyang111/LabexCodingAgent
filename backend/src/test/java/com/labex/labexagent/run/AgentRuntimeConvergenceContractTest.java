package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentRuntimeConvergenceContractTest {

    @Test
    void productionRecoveryEntrypointsDoNotExposeTheRemovedBooleanCompatibilityPaths() throws Exception {
        String lifecycle = source("src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java");
        String taskService = source("src/main/java/com/labex/labexagent/service/AgentTaskService.java");
        String workspaceScheduler = source("src/main/java/com/labex/labexagent/run/AgentWorkspaceAdmissionScheduler.java");

        assertFalse(lifecycle.contains("beginScheduledRetry("));
        assertFalse(taskService.contains("beginInteractionResume("));
        assertFalse(taskService.contains("beginWorkspaceResume("));
        assertFalse(taskService.contains("beginEnvironmentResume("));
        assertFalse(workspaceScheduler.contains("taskService.beginWorkspaceResume("));
    }

    @Test
    void releaseLauncherChecksForAnExistingLabexRuntimeBeforeBuilding() throws Exception {
        String launcher = Files.readString(
                Path.of("scripts/start-release.ps1"), StandardCharsets.UTF_8);

        int definition = launcher.indexOf("function Assert-NoExistingLabexBackend");
        int invocation = launcher.indexOf("Assert-NoExistingLabexBackend", definition + 1);
        assertTrue(definition >= 0);
        assertTrue(invocation > definition);
        assertTrue(launcher.contains("com\\.labex\\.LabexAgentApplication"));
        assertTrue(launcher.contains("before building"));
        assertTrue(launcher.contains("$PreflightOnly"));
    }

    private String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }
}