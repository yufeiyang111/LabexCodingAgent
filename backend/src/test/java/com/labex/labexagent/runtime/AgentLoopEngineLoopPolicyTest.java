package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineLoopPolicyTest {
    private final String engine = read("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java");
    private final String config = read("src/main/resources/application.yml");
    private final String taskService = read("src/main/java/com/labex/labexagent/service/AgentTaskService.java");

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

    @Test
    void restoresCurrentEpochNonProgressBudgetAndDurablyProjectsEveryUpdate() {
        assertTrue(engine.contains("currentEpochLoopGuardProgress("));
        assertTrue(engine.contains("restoreNonProgressIterations("));
        assertTrue(engine.contains("LOOP_GUARD_PROGRESS"));
        assertTrue(engine.contains("recordLoopNoProgress("));
    }

    @Test
    void durablyProjectsEachModelInvocationAsARecoverableStepBoundary() {
        assertTrue(engine.contains("MODEL_STEP_STARTED"));
        assertTrue(engine.contains("MODEL_STEP_COMPLETED"));
        assertTrue(engine.contains("MODEL_STEP_FAILED"));
        assertTrue(engine.contains("MODEL_STEP_BLOCKED"));
        assertTrue(engine.contains("MODEL_STEP_INTERRUPTED"));
        assertTrue(engine.contains("projectModelStepStarted("));
        assertTrue(engine.contains("projectModelStepCompleted("));
    }

    @Test
    void restoresTheCurrentEpochToolSuffixBeforeResumingTheModelLoop() {
        assertTrue(engine.contains("this.restoreLoopGuardHistory(loopGuard, ctx);"));
        assertTrue(engine.contains("currentEpochToolHistory("));
        assertTrue(engine.contains("restoreDurableToolCall("));
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
    void doesNotTreatTodoOrPlanCompletionAsABatchBlockingPrecondition() {
        assertFalse(engine.contains("isMissingPlanCompletion("));
        assertFalse(engine.contains("unfinished_plan"));
    }

    @Test
    void boundsCompletionEvidenceRejectionsBeforeTheGenericLoopFuse() {
        assertTrue(engine.contains("FINALIZATION_BLOCKED"));
        assertTrue(engine.contains("finalizationRecoveryService.decide("));
        assertTrue(engine.contains("finalization_recovery_exhausted"));
        assertTrue(config.contains("finalization-recovery-limit: ${LABEX_AGENT_FINALIZATION_RECOVERY_LIMIT:1}"));
    }


    @Test
    void loopGuardStopUsesTheRecoverableDurableLifecyclePathAndDoesNotEmitSyntheticFinalization() {
        int recoveryCall = engine.indexOf("this.taskService.waitForLoopGuardRecovery(");
        int emitterComplete = engine.indexOf("emitter.complete();", recoveryCall);

        assertTrue(recoveryCall >= 0);
        assertTrue(taskService.contains("\"LOOP_GUARD_STOPPED\""));
        assertTrue(emitterComplete > recoveryCall);
        String stopBlock = engine.substring(recoveryCall, emitterComplete);
        assertTrue(stopBlock.contains("this.sendPersistedEvent("));
        assertFalse(stopBlock.contains("failTaskAndProject("));
        assertFalse(stopBlock.contains("streamFinal("));
    }

}
