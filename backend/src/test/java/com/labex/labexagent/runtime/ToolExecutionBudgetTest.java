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
    void derivesBuildSizedBudgetsForMavenAndNodeShellCommands() {
        JsonObject maven = new JsonObject();
        maven.addProperty("command", "mvn -q test");
        assertEquals(305_000L, ToolExecutionBudget.timeoutMs("shell", maven));

        JsonObject npm = new JsonObject();
        npm.addProperty("command", "cd frontend&&npm install");
        assertEquals(245_000L, ToolExecutionBudget.timeoutMs("shell", npm));
    }

    @Test
    void honorsExplicitCommandTimeoutButKeepsItsSmallWatchdogMarginBounded() {
        JsonObject args = new JsonObject();
        args.addProperty("timeout_seconds", 120);
        assertEquals(125_000L, ToolExecutionBudget.timeoutMs("shell", args));

        args.addProperty("timeout_seconds", 900);
        assertEquals(605_000L, ToolExecutionBudget.timeoutMs("run_tests", args));

        JsonObject millisecondArgs = new JsonObject();
        millisecondArgs.addProperty("timeout", 1_234);
        assertEquals(6_234L, ToolExecutionBudget.timeoutMs("shell", millisecondArgs));
    }
}
