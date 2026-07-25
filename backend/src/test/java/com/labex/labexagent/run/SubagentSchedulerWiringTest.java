package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SubagentSchedulerWiringTest {

    @Test
    void createsTheSchedulerUsingItsServiceConstructor() {
        new ApplicationContextRunner()
                .withBean(AgentSubagentService.class, () -> mock(AgentSubagentService.class))
                .withBean(AgentSubagentEventService.class, () -> mock(AgentSubagentEventService.class))
                .withBean(SubagentResultSummaryService.class, () -> mock(SubagentResultSummaryService.class))
                .withBean(SubagentScheduler.class)
                .run(context -> assertThat(context).hasSingleBean(SubagentScheduler.class));
    }
}