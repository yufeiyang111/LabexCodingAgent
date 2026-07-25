package com.labex.labexagent.run;
import com.labex.entity.AgentSubagent;import java.time.LocalDateTime;import java.util.Map;import org.springframework.stereotype.Service;
@Service public class SubagentResultSummaryService {
 private final AgentSubagentService subagents;private final AgentSubagentEventService events;private final AgentRunLifecycleService parentEvents;
 public SubagentResultSummaryService(AgentSubagentService subagents,AgentSubagentEventService events,AgentRunLifecycleService parentEvents){this.subagents=subagents;this.events=events;this.parentEvents=parentEvents;}
 public void complete(AgentSubagent agent,String summary,boolean success){SubagentState target=success?SubagentState.COMPLETED:SubagentState.FAILED;agent.setSummary(summary);agent.setUpdateTime(LocalDateTime.now());subagents.transition(agent,target);events.append(agent.getSubagentId(),"FINAL",summary);parentEvents.appendEvent(agent.getTaskId(),"SUBAGENT_SUMMARY",Map.of("subagentId",agent.getSubagentId(),"identity",agent.getIdentity(),"success",success,"summary",summary==null?"":summary),"subagent-"+agent.getSubagentId()+"-summary");}
}
