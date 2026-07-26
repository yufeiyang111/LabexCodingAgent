package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ToolExecutionBudgetTest {

    @Test
    void usesShortBudgetForReadOnlyToolsAndLongerBudgetForRemoteTools() {
        assertEquals(30_000L, ToolExecutionBudget.timeoutMs("read_file", new JsonObject()));
        assertEquals(90_000L, ToolExecutionBudget.timeoutMs("mcp_call", new JsonObject()));
    }

    @Test
    void honorsExplicitCommandTimeoutButKeepsItsSmallWatchdogMarginBounded() {
        JsonObject args = new JsonObject();
        args.addProperty("timeout_seconds", 120);
        assertEquals(125_000L, ToolExecutionBudget.timeoutMs("shell", args));

        args.addProperty("timeout_seconds", 900);
        assertEquals(605_000L, ToolExecutionBudget.timeoutMs("run_tests", args));
    }
}
