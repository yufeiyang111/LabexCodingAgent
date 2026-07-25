package com.labex.labexagent.run;
import com.labex.entity.AgentSubagent;import com.labex.entity.StudentProject;import com.labex.labexagent.runtime.AgentContext;
public final class SubagentContextFactory {
 private SubagentContextFactory(){}
 public static AgentContext create(String parentSessionId,Integer studentId,StudentProject project,String conversationId,Long taskId,AgentSubagent subagent){
  if(subagent==null||subagent.getSubagentId()==null)throw new IllegalArgumentException("persisted subagent is required");
  AgentContext context=AgentContext.create(parentSessionId+":subagent:"+subagent.getSubagentId(),studentId,project,conversationId,taskId);
  context.setMode("subagent");context.setStage("subagent:"+subagent.getIdentity());return context;
 }
}
