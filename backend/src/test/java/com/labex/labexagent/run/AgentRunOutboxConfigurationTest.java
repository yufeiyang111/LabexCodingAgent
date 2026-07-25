package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentRunOutboxConfigurationTest {

    @Test
    void providesTheDefaultSinkWhenNoExternalQueueSinkIsConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRunOutboxConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(AgentRunOutboxSink.class));
    }
}
