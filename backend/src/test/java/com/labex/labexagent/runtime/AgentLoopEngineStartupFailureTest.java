package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentLoopEngineStartupFailureTest {

    @Test
    void reportsStartupFailureWithoutRequiringAConversationOrTask() throws Exception {
        AgentSsePublisher publisher = mock(AgentSsePublisher.class);
        AgentLoopEngine engine = new AgentLoopEngine(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        engine.reportStartupFailure(publisher, "en", new IllegalStateException("model configuration unavailable"));

        ArgumentCaptor<Object> errorPayload = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(publisher);
        order.verify(publisher).sendTransient(eq("ERROR"), errorPayload.capture());
        order.verify(publisher).sendTransient(eq("DONE"), any());

        assertThat(errorPayload.getValue()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) errorPayload.getValue()).get("message"))
                .asString()
                .contains("Agent startup failed")
                .contains("model configuration unavailable");
    }
}
