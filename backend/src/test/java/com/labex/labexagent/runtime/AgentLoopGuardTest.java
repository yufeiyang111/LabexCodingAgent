package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class AgentLoopGuardTest {

    @Test
    void defaultPolicyDoesNotImposeAnArbitraryTotalIterationLimit() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());

        assertEquals(AgentLoopGuard.IterationAction.CONTINUE, guard.beforeIteration(10_000).action());
    }

    @Test
    void optionalHardLimitRemainsAvailableAsAConfigurableFinalFuse() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setHardMaxIterations(40);
        AgentLoopGuard guard = new AgentLoopGuard(properties);

        assertEquals(AgentLoopGuard.IterationAction.CONTINUE, guard.beforeIteration(40).action());
        assertEquals(AgentLoopGuard.IterationAction.STOP, guard.beforeIteration(41).action());
    }

    @Test
    void thirdIdenticalToolInputRequestsAutomaticStrategySwitchThenEscalates() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject args = new JsonObject();
        args.addProperty("path", "src/App.vue");
        args.addProperty("offset", 1);
        args.addProperty("limit", 200);

        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("read_file", args).action());
        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("read_file", args).action());
        assertEquals(AgentLoopGuard.ToolAction.SWITCH_STRATEGY, guard.beforeToolCall("read_file", args).action());
        assertEquals(AgentLoopGuard.ToolAction.REQUEST_USER, guard.beforeToolCall("read_file", args).action());
    }

    @Test
    void differentArgumentsAreNotCollapsedToTheSameDisplayTarget() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject first = new JsonObject();
        first.addProperty("path", "src/App.vue");
        first.addProperty("offset", 1);
        first.addProperty("limit", 100);
        JsonObject second = new JsonObject();
        second.addProperty("path", "src/App.vue");
        second.addProperty("offset", 101);
        second.addProperty("limit", 100);

        AgentLoopGuard.ToolDecision firstDecision = guard.beforeToolCall("read_file", first);
        AgentLoopGuard.ToolDecision secondDecision = guard.beforeToolCall("read_file", second);

        assertEquals(AgentLoopGuard.ToolAction.ALLOW, secondDecision.action());
        assertNotEquals(firstDecision.signature(), secondDecision.signature());
    }

    @Test
    void detectsShortAlternatingCyclesInsteadOfOnlyConsecutiveDuplicates() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject read = new JsonObject();
        read.addProperty("path", "pom.xml");
        JsonObject test = new JsonObject();
        test.addProperty("command", "mvn test");

        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("read_file", read).action());
        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("shell", test).action());
        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("read_file", read).action());
        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("shell", test).action());
        assertEquals(AgentLoopGuard.ToolAction.ALLOW, guard.beforeToolCall("read_file", read).action());
        assertEquals(AgentLoopGuard.ToolAction.SWITCH_STRATEGY, guard.beforeToolCall("shell", test).action());
    }

    @Test
    void escalatesRepeatedFailedCommandIdentityEvenWhenOtherStrategiesAreInterleaved() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject build = new JsonObject();
        build.addProperty("command", "npm run build");
        build.addProperty("working_directory", "frontend");
        JsonObject unavailable = new JsonObject();
        unavailable.addProperty("strategy", "offline_test");
        JsonObject shell = new JsonObject();
        shell.addProperty("command", "npm run build");
        shell.addProperty("working_directory", "frontend");

        AgentLoopGuard.ToolDecision first = guard.beforeToolCall("run_tests", build);
        guard.recordToolResult(first.signature(), false);
        AgentLoopGuard.ToolDecision unrelated = guard.beforeToolCall("run_tests", unavailable);
        guard.recordToolResult(unrelated.signature(), false);
        AgentLoopGuard.ToolDecision second = guard.beforeToolCall("run_tests", build);
        guard.recordToolResult(second.signature(), false);
        AgentLoopGuard.ToolDecision otherTool = guard.beforeToolCall("shell", shell);
        guard.recordToolResult(otherTool.signature(), false);

        assertEquals(AgentLoopGuard.ToolAction.SWITCH_STRATEGY,
                guard.beforeToolCall("run_tests", build).action());
        assertEquals(AgentLoopGuard.ToolAction.REQUEST_USER,
                guard.beforeToolCall("run_tests", build).action());
    }

    @Test
    void canonicalSignatureIgnoresJsonObjectInsertionOrder() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject first = new JsonObject();
        first.addProperty("path", "README.md");
        first.addProperty("offset", 1);
        JsonObject second = new JsonObject();
        second.addProperty("offset", 1);
        second.addProperty("path", "README.md");

        assertEquals(
                guard.beforeToolCall("read_file", first).signature(),
                guard.beforeToolCall("read_file", second).signature());
    }
    @Test
    void stopsAfterConfigurableConsecutiveNoProgressTurns() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        for (int i = 0; i < 8; i++) {
            guard.recordModelNoProgress();
        }

        assertEquals(AgentLoopGuard.IterationAction.STOP, guard.beforeIteration(1).action());
        assertEquals("non_progress", guard.beforeIteration(1).reason());
    }
}