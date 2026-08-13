package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.projectconfig.AgentRunConfigSnapshotService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolSelectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentRunProcessorWiringTest {
    @Test
    void exposesLoopProcessorsAsIndependentSpringServices() {
        new ApplicationContextRunner()
                .withBean(ToolRegistry.class, () -> mock(ToolRegistry.class))
                .withBean(AgentTaskService.class, () -> mock(AgentTaskService.class))
                .withBean(AgentRunConfigSnapshotService.class, () -> mock(AgentRunConfigSnapshotService.class))
                .withBean(AgentModelTurnExecutor.class)
                .withBean(AgentToolTurnExecutor.class)
                .withBean(AgentToolCallBatchProtocol.class)
                .withBean(AgentToolNarrator.class)
                .withBean(ToolSelectionPolicy.class)
                .withBean(ContextAdmissionService.class)
                .withBean(ContextAdmissionGate.class)
                .withBean(AgentInteractionPauser.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentModelTurnExecutor.class);
                    assertThat(context).hasSingleBean(AgentToolTurnExecutor.class);
                    assertThat(context).hasSingleBean(AgentToolCallBatchProtocol.class);
                    assertThat(context).hasSingleBean(AgentToolNarrator.class);
                    assertThat(context).hasSingleBean(ToolSelectionPolicy.class);
                    assertThat(context).hasSingleBean(ContextAdmissionService.class);
                    assertThat(context).hasSingleBean(ContextAdmissionGate.class);
                    assertThat(context).hasSingleBean(AgentInteractionPauser.class);
                });
    }

    @Test
    void exposesTheTaskEpochSnapshotServiceInjectionPoints() throws Exception {
        assertThat(AgentTaskService.class.getDeclaredMethod(
                "setRunConfigSnapshotService", AgentRunConfigSnapshotService.class)).isNotNull();
        assertThat(AgentLoopEngine.class.getDeclaredMethod(
                "setRunConfigSnapshotService", AgentRunConfigSnapshotService.class)).isNotNull();
    }
}
