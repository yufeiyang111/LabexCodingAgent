package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.labex.entity.AgentSubagent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SubagentResultSummaryServiceTest {

    @Test
    void forwardsOnlyTheVisibleTerminalSummaryToTheParentRun() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        AgentSubagentEventService events = mock(AgentSubagentEventService.class);
        AgentRunLifecycleService parent = mock(AgentRunLifecycleService.class);
        AgentSubagent agent = new AgentSubagent();
        agent.setSubagentId(3L);
        agent.setTaskId(9L);
        agent.setIdentity("reviewer");
        agent.setStatus("running");

        new SubagentResultSummaryService(subagents, events, parent)
                .complete(agent, "looks <think>private chain</think> good", true);

        assertEquals("looks  good", agent.getSummary());
        verify(subagents).transition(agent, SubagentState.COMPLETED);
        verify(events).append(3L, "FINAL", "looks  good");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(parent).appendEvent(eq(9L), eq("SUBAGENT_SUMMARY"), payload.capture(), eq("subagent-3-summary"));
        assertEquals("looks  good", payload.getValue().get("summary"));
    }
}
