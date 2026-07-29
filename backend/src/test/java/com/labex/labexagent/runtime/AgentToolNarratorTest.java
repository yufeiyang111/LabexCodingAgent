package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

class AgentToolNarratorTest {
    private final AgentToolNarrator narrator = new AgentToolNarrator();

    @Test
    void describesReadActionsWithoutOwningRuntimeState() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "src/App.vue");

        assertTrue(narrator.buildToolThought("read_file", args, false, "zh")
                .contains("src/App.vue"));
        assertTrue(narrator.visibleActionSummary("read_file", args, "en")
                .toLowerCase().contains("read"));
    }

    @Test
    void describesVerificationResultsAndPlanProgress() {
        JsonObject verification = new JsonObject();
        verification.addProperty("command", "npm test");
        String result = narrator.buildResultThought(
                "run_tests", verification, ToolResult.ok("Tests passed"), "en");

        assertTrue(result.contains("Verification passed"));

        JsonObject plan = new JsonObject();
        plan.addProperty("action", "update");
        assertTrue(narrator.buildToolThought("todo_write", plan, false, "zh")
                .contains("\u4efb\u52a1"));
    }

    @Test
    void keepsRecoveredToolIntentExplicit() {
        assertTrue(narrator.buildToolThought("grep", new JsonObject(), true, "en")
                .contains("standard tool format"));
    }
}

