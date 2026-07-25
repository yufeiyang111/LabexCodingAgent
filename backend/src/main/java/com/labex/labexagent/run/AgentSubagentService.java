package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentSubagent;
import com.labex.mapper.AgentSubagentMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class AgentSubagentService {
 private final AgentSubagentMapper mapper; private final SubagentPolicy policy;
 public AgentSubagentService(AgentSubagentMapper mapper, SubagentPolicy policy){this.mapper=mapper;this.policy=policy;}
 public AgentSubagent create(Long taskId,String identity,String instructions,Integer modelConfigId,int budget,String permissionsJson,String toolsJson,boolean background){
  int active=Math.toIntExact(mapper.selectCount(new LambdaQueryWrapper<AgentSubagent>().eq(AgentSubagent::getTaskId,taskId).in(AgentSubagent::getStatus,SubagentState.QUEUED.persisted(),SubagentState.RUNNING.persisted(),SubagentState.WAITING_USER.persisted())));
  policy.validateSpawn(null,active,budget); LocalDateTime now=LocalDateTime.now(); AgentSubagent agent=new AgentSubagent();
  agent.setTaskId(taskId);agent.setIdentity(identity);agent.setInstructions(instructions);agent.setModelConfigId(modelConfigId);agent.setStatus(SubagentState.QUEUED.persisted());agent.setTokenBudget(budget);agent.setTokensUsed(0);agent.setPermissionsJson(permissionsJson);agent.setToolsJson(toolsJson);agent.setBackground(background?1:0);agent.setCreateTime(now);agent.setUpdateTime(now);mapper.insert(agent);return agent;
 }
 public void transition(AgentSubagent agent,SubagentState target){
  SubagentState current=SubagentState.valueOf(agent.getStatus().toUpperCase(java.util.Locale.ROOT));if(!current.mayTransitionTo(target))throw new IllegalStateException("illegal subagent transition");agent.setStatus(target.persisted());agent.setUpdateTime(LocalDateTime.now());mapper.updateById(agent);
 }
 public void consumeTokens(AgentSubagent agent,int tokens){
  if(tokens<0)throw new IllegalArgumentException("token usage increment must be non-negative");
  int used=(agent.getTokensUsed()==null?0:agent.getTokensUsed())+tokens; int budget=agent.getTokenBudget()==null?0:agent.getTokenBudget();
  if(used>budget)throw new IllegalStateException("subagent token budget exceeded");
  agent.setTokensUsed(used);agent.setUpdateTime(LocalDateTime.now());mapper.updateById(agent);
 }
 public int cancelActiveForTask(Long taskId){
  return mapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentSubagent>()
   .eq(AgentSubagent::getTaskId,taskId).in(AgentSubagent::getStatus,SubagentState.QUEUED.persisted(),SubagentState.RUNNING.persisted(),SubagentState.WAITING_USER.persisted())
   .set(AgentSubagent::getStatus,SubagentState.CANCELLED.persisted()).set(AgentSubagent::getUpdateTime,LocalDateTime.now()));
 }

}
