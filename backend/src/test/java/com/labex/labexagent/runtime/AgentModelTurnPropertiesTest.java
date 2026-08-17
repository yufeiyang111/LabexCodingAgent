package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentModelTurnPropertiesTest {

    @Test
    void usesAConfigurableFiveMinuteWatchdogByDefault() {
        AgentModelTurnProperties properties = new AgentModelTurnProperties();

        assertEquals(300_000L, properties.getTotalTimeoutMs());
    }

    @Test
    void allowsDisablingTheOuterWatchdogWithoutChangingProviderTimeouts() {
        AgentModelTurnProperties properties = new AgentModelTurnProperties();
        properties.setTotalTimeoutMs(0L);

        assertEquals(0L, properties.getTotalTimeoutMs());
    }
}