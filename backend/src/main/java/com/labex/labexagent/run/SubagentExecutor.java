package com.labex.labexagent.run;
import com.labex.entity.AgentSubagent;import com.labex.labexagent.runtime.AgentContext;
@FunctionalInterface public interface SubagentExecutor { String execute(AgentContext context,AgentSubagent subagent) throws Exception; }
