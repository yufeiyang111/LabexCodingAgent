package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopGuardConfigContractTest {

    @Test
    void guardMustUseTheInjectedLoopPropertiesAndKeepStateLocal() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("new AgentLoopProperties()"));
        assertTrue(source.contains("private final AgentLoopProperties properties"));
        assertTrue(source.contains("private final Deque<String> recentToolSignatures"));
        assertTrue(source.contains("private final Set<String> challengedPatterns"));
        assertThrows(IllegalArgumentException.class, () -> new AgentLoopGuard(null));
    }
}