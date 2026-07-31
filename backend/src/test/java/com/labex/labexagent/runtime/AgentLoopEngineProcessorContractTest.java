package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineProcessorContractTest {

    @Test
    void loopEngineMustUseInjectedProcessorBeansInsteadOfAdHocDefaults() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("new AgentModelTurnExecutor("));
        assertFalse(source.contains("new AgentToolTurnExecutor("));
        assertFalse(source.contains("new AgentToolCallBatchProtocol()"));
        assertFalse(source.contains("new AgentProviderMessageProjector()"));
        assertFalse(source.contains("new AgentToolNarrator()"));
        assertFalse(source.contains("new ToolSelectionPolicy()"));
        assertFalse(source.contains("new ContextAdmissionService()"));
        assertFalse(source.contains("new ContextAdmissionGate()"));
        assertFalse(source.contains("new AgentInteractionPauser("));
        assertTrue(source.contains("AgentProviderMessageProjector providerMessageProjector"));
        assertTrue(source.contains("AgentToolTurnExecutor toolTurnExecutor"));
    }
}