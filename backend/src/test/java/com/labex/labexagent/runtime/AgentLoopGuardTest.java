package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.tool.ToolResult;
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
    void allowsAFailedPlanCompletionToRetryAfterDurableVerificationProgresses() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject complete = new JsonObject();
        complete.addProperty("action", "complete");
        complete.addProperty("task_index", 3);

        AgentLoopGuard.ToolDecision first = guard.beforeToolCall("create_plan", complete, "verification=0");
        guard.recordToolResult(first.signature(), false);
        AgentLoopGuard.ToolDecision second = guard.beforeToolCall("create_plan", complete, "verification=0");
        guard.recordToolResult(second.signature(), false);

        assertEquals(AgentLoopGuard.ToolAction.ALLOW,
                guard.beforeToolCall("create_plan", complete, "verification=1;source=run_tests").action());
    }

    @Test
    void restoresDurableNonProgressBudgetBeforeTheNextIteration() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setMaxNonProgressIterations(3);
        AgentLoopGuard guard = new AgentLoopGuard(properties);

        guard.restoreNonProgressIterations(3);

        assertEquals(AgentLoopGuard.IterationAction.STOP, guard.beforeIteration(1).action());
        assertEquals(3, guard.beforeIteration(1).nonProgressIterations());
    }

    @Test
    void restoresRecentDurableAttemptsBeforeEvaluatingTheNextCall() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject args = new JsonObject();
        args.addProperty("path", "src/App.vue");

        guard.restoreDurableToolCall("read_file", args);
        guard.restoreDurableToolCall("read_file", args);

        assertEquals(AgentLoopGuard.ToolAction.SWITCH_STRATEGY,
                guard.beforeToolCall("read_file", args).action());
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
    void observedNonZeroShellExitFeedsRepeatedFailureProtection() {
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", "npm run build");
        ToolResult observedNonZero = ToolResult.fromObservedProcessExecution(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 1, 20, "build failed", false),
                "bash", ".", null);

        AgentLoopGuard.ToolDecision first = guard.beforeToolCall("shell", arguments);
        guard.recordToolResult(first.signature(), observedNonZero);
        AgentLoopGuard.ToolDecision second = guard.beforeToolCall("shell", arguments);
        guard.recordToolResult(second.signature(), observedNonZero);

        assertEquals(AgentLoopGuard.ToolAction.SWITCH_STRATEGY,
                guard.beforeToolCall("shell", arguments).action());
    }

    @Test
    void distinctFailedToolCallsDoNotTripTheModelNoProgressFuse() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setMaxNonProgressIterations(3);
        AgentLoopGuard guard = new AgentLoopGuard(properties);

        for (int attempt = 0; attempt < 4; attempt++) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("path", "src/missing-" + attempt + ".java");
            AgentLoopGuard.ToolDecision decision = guard.beforeToolCall("read_file", arguments);
            assertEquals(AgentLoopGuard.ToolAction.ALLOW, decision.action());
            guard.recordToolResult(decision.signature(), false);
        }

        assertEquals(0, guard.nonProgressIterations());
        assertEquals(AgentLoopGuard.IterationAction.CONTINUE, guard.beforeIteration(1).action());
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