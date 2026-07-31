package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentTranscriptProjectionContractTest {

    @Test
    void providerProjectionMustNotSilentlyFallBackToAnUncompactedOrAdHocRuntime() throws Exception {
        String projection = source("src/main/java/com/labex/labexagent/runtime/AgentTranscriptProjectionService.java");
        String projector = source("src/main/java/com/labex/labexagent/runtime/AgentProviderMessageProjector.java");

        assertFalse(projection.contains("@Autowired(required = false)"));
        assertFalse(projection.contains("new AgentProviderMessageProjector()"));
        assertFalse(projection.contains("compactionService != null"));
        assertTrue(projection.contains("AgentCompactionService compactionService"));
        assertTrue(projection.contains("AgentProviderMessageProjector providerProjector"));
        assertTrue(projector.contains("@Service"));
    }

    private String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }
}