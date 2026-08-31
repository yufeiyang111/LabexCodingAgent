package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEnginePolicyContractTest {

    @Test
    void loopEngineMustUseInjectedPolicyAndDurableProgressBeans() throws Exception {
        String engine = source("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java");
        String classifier = source("src/main/java/com/labex/labexagent/commandsecurity/CommandClassifier.java");
        String normalizer = source("src/main/java/com/labex/labexagent/commandsecurity/CommandNormalizer.java");
        String checkpoint = source("src/main/java/com/labex/labexagent/runtime/AgentCheckpointStore.java");

        assertFalse(engine.contains("new CommandClassifier()"));
        assertFalse(engine.contains("new AgentCheckpointStore()"));
        assertFalse(engine.contains("new CommandFailureGuard("));
        assertFalse(engine.contains("new AgentRecoveryProperties()"));
        assertFalse(engine.contains("new AgentLoopProperties()"));
        assertFalse(engine.contains("new AgentRequestTokenEstimator()"));
        assertTrue(engine.contains("void setCommandClassifier"));
        assertTrue(engine.contains("void setLegacyCheckpointMigrationService"));
        assertTrue(engine.contains("void setContextCompactionServices"));
        assertTrue(engine.contains("void setRunProgressProjectionService"));
        assertTrue(engine.contains("requireLegacyCheckpointMigrationService().restoreOrMigrate"));
        assertFalse(engine.contains("checkpointStore.loadLegacy"));
        assertTrue(engine.contains("providerMessagesForInvocation"));
        assertTrue(engine.contains("ensureObjectiveAnchor"));
        assertFalse(engine.contains("<agent_runtime_projection"));
        assertFalse(engine.contains("checkpointStore.save"));
        assertFalse(engine.contains("restoreInto(ctx)"));
        assertFalse(engine.contains("checkpointStore::renderForPrompt"));
        assertTrue(classifier.contains("@Service"));
        assertTrue(normalizer.contains("@Service"));
        assertTrue(checkpoint.contains("@Service"));
    }

    private String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }
}