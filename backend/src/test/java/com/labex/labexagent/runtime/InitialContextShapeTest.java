package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.service.AgentSkillService;
import org.junit.jupiter.api.Test;

class InitialContextShapeTest {

    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void leanFirstMessageKeepsRulesAndMemoryButDropsPreloadedBundle() {
        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "<agent_mode name=\"build\">", "project rules", "durable workspace facts", "", "", "");

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
                "", "", "", "previous run log", "checkpoint after disconnect", "");

        assertThat(message).contains("<latest_agent_run_log", "previous run log");
        assertThat(message).contains("<agent_checkpoint", "checkpoint after disconnect");
    }

    @Test
    void leanFirstMessageStaysUnderEightThousandTokensAtHardLimits() {
        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "mode policy", "r".repeat(10_000), "m".repeat(2_000), "", "",
                AgentSkillService.renderCatalogBlock(java.util.List.of(
                        new AgentSkillService.CatalogEntry("review", "review", "d".repeat(500),
                                AgentSkillService.SOURCE_PROJECT, ".skill/review/SKILL.md"))));

        assertThat(estimator.estimateValue(message)).isLessThanOrEqualTo(8_000);
    }

    @Test
    void leanFirstMessageWithoutInputsIsBlank() {
        assertThat(AgentLoopEngine.buildLeanInitialContextMessage("", "", "", "", "", "").isBlank()).isTrue();
    }

    /**
     * 没有这一句，skill 工具就是一个没有上下文理由的孤岛 —— 这正是用户级技能全历史 0 次调用的根因。
     */
    @Test
    void skillCatalogIsAnnouncedSoTheModelKnowsSkillsExist() {
        String catalog = AgentSkillService.renderCatalogBlock(java.util.List.of(
                new AgentSkillService.CatalogEntry("code-review", "code-review",
                        "Use when reviewing a diff", AgentSkillService.SOURCE_PROJECT,
                        ".skill/review/SKILL.md")));

        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "mode", "", "", "", "", catalog);

        assertThat(message).contains("Use the skill tool", "code-review", "<available_skills>");
        assertThat(message).contains(".skill/review/SKILL.md");
    }

    @Test
    void skillCatalogIsOmittedWhenThereAreNoSkills() {
        String message = AgentLoopEngine.buildLeanInitialContextMessage(
                "mode", "", "", "", "", "");

        assertThat(message).doesNotContain("<available_skills>");
        assertThat(message).doesNotContain("skill tool");
    }
}
