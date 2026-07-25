package com.labex.labexagent.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRunOutboxConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentRunOutboxSink.class)
    public AgentRunOutboxSink agentRunOutboxSink(ApplicationEventPublisher eventPublisher) {
        return new InProcessAgentRunOutboxSink(eventPublisher);
    }
}
