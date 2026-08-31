package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 把子 Agent 的终态结果投影回父任务。 */
@Service
public class SubagentResultSummaryService {
    private final AgentSubagentService subagents;
    private final AgentSubagentEventService events;
    private final AgentRunLifecycleService parentEvents;

    public SubagentResultSummaryService(AgentSubagentService subagents,
                                        AgentSubagentEventService events,
                                        AgentRunLifecycleService parentEvents) {
        this.subagents = subagents;
        this.events = events;
        this.parentEvents = parentEvents;
    }

    public void complete(AgentSubagent agent, String summary, boolean success) {
        String safeSummary = InternalReasoningBoundary.stripVisible(summary);
        SubagentState target = success ? SubagentState.COMPLETED : SubagentState.FAILED;
        agent.setSummary(safeSummary);
        agent.setUpdateTime(LocalDateTime.now());
        subagents.transition(agent, target);
        events.append(agent.getSubagentId(), "FINAL", safeSummary);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subagentId", agent.getSubagentId());
        payload.put("identity", agent.getIdentity());
        payload.put("success", success);
        payload.put("status", target.persisted());
        payload.put("summary", safeSummary == null ? "" : safeSummary);
        payload.put("tokensUsed", agent.getTokensUsed());
        payload.put("tokenBudget", agent.getTokenBudget());
        if (agent.getParentToolCallId() != null && !agent.getParentToolCallId().isBlank()) {
            payload.put("toolCallId", agent.getParentToolCallId());
        }
        com.labex.entity.AgentRunEvent summaryEvent = parentEvents.appendEvent(agent.getTaskId(), "SUBAGENT_SUMMARY", payload,
                "subagent-" + agent.getSubagentId() + "-summary");
        com.labex.labexagent.runtime.AgentLoopEngine.publishToActiveTask(agent.getTaskId(), "SUBAGENT_SUMMARY", payload,
                summaryEvent == null ? null : summaryEvent.getSequenceNumber());
    }
}