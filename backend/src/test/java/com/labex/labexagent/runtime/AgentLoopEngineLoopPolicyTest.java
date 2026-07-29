package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineLoopPolicyTest {
    private final String engine = read("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java");
    private final String config = read("src/main/resources/application.yml");

    @Test
    void replacesTheFixedThirtyTurnLimitWithConfigurableFinalFuse() {
        assertFalse(engine.contains("DEFAULT_MAX_ITERATIONS"));
        assertTrue(engine.contains("new AgentLoopGuard(loopProperties)"));
        assertTrue(engine.contains("beforeIteration(i)"));
        assertTrue(config.contains("hard-max-iterations: ${LABEX_AGENT_HARD_MAX_ITERATIONS:0}"));
        assertTrue(config.contains("max-non-progress-iterations: ${LABEX_AGENT_MAX_NON_PROGRESS_ITERATIONS:8}"));
    }

    @Test
    void appliesTheSameLoopGuardToNativeAndRecoveredToolCalls() {
        assertTrue(occurrences(engine, ".beforeToolCall(") >= 2);
        assertTrue(engine.contains("ToolAction.REQUEST_USER"));
        assertTrue(engine.contains("this.askUserQuestion("));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    @Test
    void treatsCompletingAMissingPlanAsABatchBlockingPreconditionFailure() {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("action", "complete");
        assertTrue(AgentLoopEngine.isMissingPlanCompletion(
                "create_plan", args, com.labex.labexagent.tool.ToolResult.failed("failure_code=PLAN_MISSING")));
    }

}
