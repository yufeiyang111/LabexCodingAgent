package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.run.AgentToolOutputProperties;
import com.labex.labexagent.service.ProjectIndexService;
import org.junit.jupiter.api.Test;

class AgentContextManagerToolOutputProjectionTest {

    @Test
    void passesOutputAtOpenCodeDefaultByteAndLineLimitsToTheModelUnchanged() {
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class));
        String output = "x".repeat(50 * 1024);

        AgentContextManager.ToolResultProjection projection = manager.compactToolResultProjection(
                "run_tests", output, true);

        assertThat(projection.truncated()).isFalse();
        assertThat(projection.content()).isEqualTo(output);
    }

    @Test
    void respectsOpenCodeUtf8ByteLimitForCjkAndEmojiWithoutCorruptingThePayload() {
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class));
        String cjkAtLimit = "?".repeat(17_066);
        String emojiAtLimit = "??".repeat(12_800);

        AgentContextManager.ToolResultProjection cjkProjection = manager.compactToolResultProjection(
                "read_file", cjkAtLimit, true);
        AgentContextManager.ToolResultProjection emojiProjection = manager.compactToolResultProjection(
                "read_file", emojiAtLimit, true);

        assertThat(cjkProjection.truncated()).isFalse();
        assertThat(cjkProjection.content()).isEqualTo(cjkAtLimit);
        assertThat(emojiProjection.truncated()).isFalse();
        assertThat(emojiProjection.content()).isEqualTo(emojiAtLimit);
    }

    @Test
    void respectsOpenCodeLineLimitWithoutPruningAnExactlyTwoThousandLineOutput() {
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class));
        String output = "line\n".repeat(1_999) + "line";

        AgentContextManager.ToolResultProjection projection = manager.compactToolResultProjection(
                "search_code", output, true);

        assertThat(projection.truncated()).isFalse();
        assertThat(projection.content()).isEqualTo(output);
    }

    @Test
    void truncatesTheTwoThousandAndFirstLineForTheModel() {
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class));
        String output = "line\n".repeat(2_000) + "line";

        AgentContextManager.ToolResultProjection projection = manager.compactToolResultProjection(
                "search_code", output, true);

        assertThat(projection.truncated()).isTrue();
        assertThat(projection.content()).contains("source_lines=2001");
        assertThat(projection.content()).contains("direction=head");
    }

    @Test
    void retainsTheTailOfOversizedShellOutputWithinTheConfiguredModelBudget() {
        AgentToolOutputProperties properties = new AgentToolOutputProperties();
        properties.setModelMaxBytes(1_024);
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class), properties);
        String output = "start\n" + "x".repeat(1_100) + "\nTraceback: final failure";

        AgentContextManager.ToolResultProjection projection = manager.compactToolResultProjection(
                "run_tests", output, true);

        assertThat(projection.truncated()).isTrue();
        assertThat(projection.content()).contains("direction=tail");
        assertThat(projection.content()).contains("Traceback: final failure");
    }

    @Test
    void truncatedModelProjectionTellsTheModelHowToReopenTheDurableOutput() {
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class));

        AgentContextManager.ToolResultProjection projection = manager.compactToolResultProjection(
                "run_tests", "x".repeat(50 * 1024 + 1), true);

        assertThat(projection.truncated()).isTrue();
        assertThat(projection.content()).contains("tool_result_pruned");
    }
}
