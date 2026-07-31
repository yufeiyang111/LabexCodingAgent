package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentTaskServiceFallbackContractTest {

    @Test
    void taskServiceMustNotKeepASecondStatusWriterOrOptionalRuntimeDependencies() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/service/AgentTaskService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("lifecycleService == null"));
        assertFalse(source.contains("lifecycleService != null"));
        assertFalse(source.contains("@Autowired(required = false)"));
        assertFalse(source.contains("LambdaUpdateWrapper<AgentTask> update"));
        assertFalse(source.contains("update.set(AgentTask::getStatus, status)"));
        assertTrue(source.contains("AgentRunExecutionLeaseService executionLeaseService"));
        assertTrue(source.contains("BackgroundRunWorktreeService backgroundWorktreeService"));
    }
}