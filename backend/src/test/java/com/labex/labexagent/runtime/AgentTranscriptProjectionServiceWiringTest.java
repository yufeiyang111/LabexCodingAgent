package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentTranscriptProjectionServiceWiringTest {

    @Test
    void createsProjectionServiceWithItsDurableTranscriptDependency() {
        new ApplicationContextRunner()
                .withBean(AgentRunTranscriptService.class, () -> mock(AgentRunTranscriptService.class))
                .withBean(AgentProviderMessageProjector.class, AgentProviderMessageProjector::new)
                .withBean(AgentCompactionService.class, () -> mock(AgentCompactionService.class))
                .withBean(AgentTranscriptProjectionService.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(AgentTranscriptProjectionService.class));
    }
}
