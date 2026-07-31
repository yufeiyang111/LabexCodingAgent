package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineWiringContractTest {

    @Test
    void legacyConstructorsMustNotCreateAnUnwiredRuntime() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        assertEquals(1, count(source, "public AgentLoopEngine("));
        assertFalse(source.contains("interactionService, null, null, null, null, null"));
    }

    @Test
    void coreAgentLoopDependenciesMustNotBeOptionalInSpringRuntime() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("@Autowired(required = false)"));
        assertTrue(source.contains("void setExecutionLeaseServices"));
        assertTrue(source.contains("void setRunFinalizer"));
        assertTrue(source.contains("void setArtifactService"));
        assertTrue(source.contains("void setToolCallJournalService"));
        assertTrue(source.contains("void setRunInteractionService"));
        assertTrue(source.contains("void setTranscriptService"));
        assertTrue(source.contains("void setTranscriptProjectionService"));
        assertFalse(source.contains("runInteractionService != null"));
        assertTrue(source.contains("this.transcriptService = requireRuntimeDependency"));
        assertTrue(source.contains("this.transcriptProjectionService = requireRuntimeDependency"));
        assertTrue(source.contains("this.runInteractionService = requireRuntimeDependency"));
        assertFalse(source.contains("runFinalizer == null"));
        assertFalse(source.contains("artifactService == null || context"));
        assertFalse(source.contains("toolCallJournalService == null"));
        assertFalse(source.contains("toolCallJournalService != null"));
        assertTrue(source.contains("void setContextCompactionServices"));
        assertTrue(source.contains("void setProjectCheckoutLeaseServices"));
    }
    private int count(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
