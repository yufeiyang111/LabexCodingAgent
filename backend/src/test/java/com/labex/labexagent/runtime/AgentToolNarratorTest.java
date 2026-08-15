package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
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
    void describesCompletedNonZeroShellResultsWithoutCallingThemVerificationPasses() {
        ToolResult result = ToolResult.fromObservedProcessExecution(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 1, 12, "no matching process", false),
                "bash", ".", null);

        String thought = narrator.buildResultThought("shell", new JsonObject(), result, "zh");

        assertTrue(thought.contains("退出码为 1"));
        assertTrue(thought.contains("不能直接视为验证通过"));
    }
    @Test
    void doesNotPutTruncatedShellSourceIntoTheVisibleFileChangeNarrative() {
        JsonObject args = new JsonObject();
        args.addProperty("command", "cd /workspace/work-4425 && python3 <<'EOF'\nfrom app import create_app\n# a long inline script that updates the target file\nEOF");
        ToolResult result = ToolResult.ok("exit=0").withPendingChangeId("change-1");

        String thought = narrator.buildResultThought("shell", args, result, "zh");

        assertEquals("\u547d\u4ee4\u5df2\u4fee\u6539\u5de5\u4f5c\u533a\u6587\u4ef6\uff0c\u53d8\u66f4\u5df2\u8bb0\u5f55\uff0c\u4e0b\u4e00\u6b65\u505a\u9a8c\u8bc1\u3002", thought);
        assertFalse(thought.contains("/workspace"));
        assertFalse(thought.contains("..."));
    }

    @Test
    void keepsTheConcreteFilePathForDirectFileEditChangeNarratives() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "src/app.py");
        ToolResult result = ToolResult.ok("written").withPendingChangeId("change-2");

        String thought = narrator.buildResultThought("edit_file", args, result, "en");

        assertTrue(thought.contains("src/app.py"));
    }

    @Test
    void keepsRecoveredToolIntentExplicit() {
        assertTrue(narrator.buildToolThought("grep", new JsonObject(), true, "en")
                .contains("standard tool format"));
    }
}
