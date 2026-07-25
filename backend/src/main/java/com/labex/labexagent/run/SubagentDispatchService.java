package com.labex.labexagent.run;
import com.labex.entity.AgentSubagent;import com.labex.entity.StudentProject;import java.util.concurrent.CompletableFuture;import org.springframework.stereotype.Service;
@Service public class SubagentDispatchService {
 private final AgentSubagentService subagents;private final SubagentScheduler scheduler;private final LlmSubagentExecutor executor;
 public SubagentDispatchService(AgentSubagentService subagents,SubagentScheduler scheduler,LlmSubagentExecutor executor){this.subagents=subagents;this.scheduler=scheduler;this.executor=executor;}
 public Dispatch dispatch(String parentSession,Integer studentId,StudentProject project,String conversationId,Long taskId,String identity,String instructions,Integer modelConfigId,int tokenBudget,String permissionsJson,String toolsJson,boolean background){AgentSubagent subagent=subagents.create(taskId,identity,instructions,modelConfigId,tokenBudget,permissionsJson,toolsJson,background);CompletableFuture<Void> completion=scheduler.schedule(parentSession,studentId,project,conversationId,taskId,subagent,executor);return new Dispatch(subagent,completion);}
 public record Dispatch(AgentSubagent subagent,CompletableFuture<Void> completion){}
}
