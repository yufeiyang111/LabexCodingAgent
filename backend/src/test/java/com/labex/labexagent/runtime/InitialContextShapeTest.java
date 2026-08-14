package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.context.AgentRequestTokenEstimator;
import org.junit.jupiter.api.Test;

class InitialContextShapeTest {

    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void leanFirstMessageKeepsRulesAndMemoryButDropsPreloadedBundle() {
        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "<agent_mode name=\"build\">", "project rules", "durable workspace facts", "", "");

        assertThat(message).contains("<project_rules", "project rules");
        assertThat(message).contains("<workspace_memory", "durable workspace facts");
        assertThat(message).doesNotContain("<environment>");
        assertThat(message).doesNotContain("<adaptive_project_context>");
        assertThat(message).doesNotContain("<repo_map>");
        assertThat(message).doesNotContain("<workspace_diagnostics>");
        assertThat(message).doesNotContain("<conversation_memory");
        assertThat(message).doesNotContain("<session_context>");
        assertThat(message).doesNotContain("<engineering_stage>");
    }

    @Test
    void leanFirstMessagePreservesResumeRecoveryContextWhenPresent() {
        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "", "", "", "previous run log", "checkpoint after disconnect");

        assertThat(message).contains("<latest_agent_run_log", "previous run log");
        assertThat(message).contains("<agent_checkpoint", "checkpoint after disconnect");
    }

    @Test
    void leanFirstMessageStaysUnderEightThousandTokensAtHardLimits() {
        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "mode policy", "r".repeat(10_000), "m".repeat(2_000), "", "");

        assertThat(estimator.estimateValue(message)).isLessThanOrEqualTo(8_000);
    }

    @Test
    void leanFirstMessageWithoutInputsIsBlank() {
        assertThat(AgentLoopEngine.buildLeanInitialContextMessage("", "", "", "", "").isBlank()).isTrue();
    }
}
