package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentSubagent;
import com.labex.entity.StudentProject;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 固化子代理→父任务的派生事件协议（前端 reducer 依赖的字段形状）。
 * 运行时重构（统一到 AgentLoopEngine）后这些 payload 字段必须保持兼容。
 */
class SubagentParentMirrorProtocolTest {

    @Test
    void startMirrorCarriesToolCallIdAndStableIdempotencyKey() {
        AgentSubagent agent = new AgentSubagent();
        agent.setSubagentId(11L);
        agent.setIdentity("API Scout (scout)");
        agent.setParentToolCallId("call_abc");
        agent.setStatus("running");

        Map<String, Object> mirror = SubagentParentMirror.startPayload(agent);
        String key = SubagentParentMirror.startKey(agent);

        assertEquals("subagent-11-progress-1", key);
        assertEquals(11L, mirror.get("subagentId"));
        assertEquals("API Scout (scout)", mirror.get("identity"));
        assertEquals(1L, mirror.get("eventSequence"));
        assertEquals("START", mirror.get("eventType"));
        assertEquals("call_abc", mirror.get("toolCallId"));
    }

    @Test
    void summaryMirrorExposesBudgetTokensAndToolCallId() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        AgentSubagentEventService events = mock(AgentSubagentEventService.class);
        AgentRunLifecycleService parentEvents = mock(AgentRunLifecycleService.class);

        AgentSubagent agent = new AgentSubagent();
        agent.setSubagentId(12L);
        agent.setTaskId(43L);
        agent.setIdentity("Deep Diver (general)");
        agent.setStatus("running");
        agent.setTokenBudget(65_536);
        agent.setTokensUsed(1_234);
        agent.setParentToolCallId("call_def");

        new SubagentResultSummaryService(subagents, events, parentEvents)
                .complete(agent, "<think>hidden</think>final answer", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(parentEvents).appendEvent(eq(43L), eq("SUBAGENT_SUMMARY"), payload.capture(),
                eq("subagent-12-summary"));
        Map<String, Object> mirror = payload.getValue();
        assertEquals(12L, mirror.get("subagentId"));
        assertEquals("Deep Diver (general)", mirror.get("identity"));
        assertEquals(Boolean.TRUE, mirror.get("success"));
        assertEquals("completed", mirror.get("status"));
        assertEquals("final answer", mirror.get("summary"));
        assertEquals(1_234, mirror.get("tokensUsed"));
        assertEquals(65_536, mirror.get("tokenBudget"));
        assertEquals("call_def", mirror.get("toolCallId"));
    }

    @Test
    void summaryMirrorOmitsToolCallIdWhenAbsent() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        AgentSubagentEventService events = mock(AgentSubagentEventService.class);
        AgentRunLifecycleService parentEvents = mock(AgentRunLifecycleService.class);

        AgentSubagent agent = new AgentSubagent();
        agent.setSubagentId(13L);
        agent.setTaskId(44L);
        agent.setIdentity("solo");
        agent.setStatus("running");

        new SubagentResultSummaryService(subagents, events, parentEvents)
                .complete(agent, "boom", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(parentEvents).appendEvent(eq(44L), eq("SUBAGENT_SUMMARY"), payload.capture(),
                eq("subagent-13-summary"));
        assertFalse(payload.getValue().containsKey("toolCallId"));
        assertEquals(Boolean.FALSE, payload.getValue().get("success"));
        assertEquals("failed", payload.getValue().get("status"));
    }
}
