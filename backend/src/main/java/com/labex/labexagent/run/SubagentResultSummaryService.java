package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import java.time.LocalDateTime;
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
        parentEvents.appendEvent(
                agent.getTaskId(),
                "SUBAGENT_SUMMARY",
                Map.of(
                        "subagentId", agent.getSubagentId(),
                        "identity", agent.getIdentity(),
                        "success", success,
                        "summary", safeSummary),
                "subagent-" + agent.getSubagentId() + "-summary");
    }
}