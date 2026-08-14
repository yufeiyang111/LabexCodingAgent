package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.service.ProjectIndexService;
import org.junit.jupiter.api.Test;

class AgentContextManagerToolOutputProjectionTest {

    @Test
    void truncatedModelProjectionTellsTheModelHowToReopenTheDurableOutput() {
        AgentContextManager manager = new AgentContextManager(mock(ProjectIndexService.class));

        AgentContextManager.ToolResultProjection projection = manager.compactToolResultProjection(
                "run_tests", "x".repeat(6_000), true);

        assertThat(projection.truncated()).isTrue();
        assertThat(projection.content()).contains("tool_result_pruned");
    }
}
