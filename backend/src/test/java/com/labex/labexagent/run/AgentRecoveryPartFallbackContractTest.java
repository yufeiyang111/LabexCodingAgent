package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentRecoveryPartFallbackContractTest {

    @Test
    void recoveryAndPartServicesMustNotSilentlyDropDurableRecoveryDependencies() throws Exception {
        String recovery = source("src/main/java/com/labex/labexagent/run/AgentRunRecoveryService.java");
        String part = source("src/main/java/com/labex/labexagent/run/AgentRunPartService.java");

        assertFalse(recovery.contains("@Autowired(required = false)"));
        assertFalse(recovery.contains("executionLeaseService != null"));
        assertFalse(recovery.contains("takeoverScheduler != null"));
        assertFalse(recovery.contains("partService != null"));
        assertFalse(recovery.contains("messageService != null"));
        assertFalse(part.contains("messageService == null"));
        assertTrue(recovery.contains("AgentRunTakeoverScheduler takeoverScheduler"));
        assertTrue(recovery.contains("AgentRunPartService partService"));
        assertTrue(recovery.contains("AgentRunMessageService messageService"));
    }

    private String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }
}