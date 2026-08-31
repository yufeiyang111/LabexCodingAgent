package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("t_agent_subagent")
public class AgentSubagent {
    @TableId(value="subagent_id", type=IdType.AUTO) private Long subagentId;
    @TableField("task_id") private Long taskId;
    @TableField("parent_subagent_id") private Long parentSubagentId;
    @TableField("identity") private String identity;
    /** 规范化子代理类型：explore / scout / general。 */
    @TableField("agent_type") private String agentType;
    @TableField("instructions") private String instructions;
    @TableField("model_config_id") private Integer modelConfigId;
    @TableField("status") private String status;
    @TableField("token_budget") private Integer tokenBudget;
    @TableField("tokens_used") private Integer tokensUsed;
    @TableField("permissions_json") private String permissionsJson;
    @TableField("tools_json") private String toolsJson;
    @TableField("background") private Integer background;
    @TableField("summary") private String summary;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("update_time") private LocalDateTime updateTime;
    /** 派发本次子代理的父任务工具调用 ID；持久化以支持重启后重新关联父级卡片。 */
    @TableField("parent_tool_call_id") private String parentToolCallId;
    /** 本子代理运行对应的独立 AgentTask；子会话 UI 通过它复用主链路回放/订阅。 */
    @TableField("child_task_id") private Long childTaskId;
    /** 派生深度：主 Agent 派发为 1，逐层 +1，硬上限见 AgentSubagentProperties.maxSpawnDepth。 */
    @TableField("spawn_depth") private Integer spawnDepth;
    public Long getSubagentId(){return subagentId;} public void setSubagentId(Long v){subagentId=v;}
    public Long getTaskId(){return taskId;} public void setTaskId(Long v){taskId=v;}
    public Long getParentSubagentId(){return parentSubagentId;} public void setParentSubagentId(Long v){parentSubagentId=v;}
    public String getIdentity(){return identity;} public void setIdentity(String v){identity=v;}
    public String getAgentType(){return agentType;} public void setAgentType(String v){agentType=v;}
    public String getInstructions(){return instructions;} public void setInstructions(String v){instructions=v;}
    public Integer getModelConfigId(){return modelConfigId;} public void setModelConfigId(Integer v){modelConfigId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Integer getTokenBudget(){return tokenBudget;} public void setTokenBudget(Integer v){tokenBudget=v;}
    public Integer getTokensUsed(){return tokensUsed;} public void setTokensUsed(Integer v){tokensUsed=v;}
    public String getPermissionsJson(){return permissionsJson;} public void setPermissionsJson(String v){permissionsJson=v;}
    public String getToolsJson(){return toolsJson;} public void setToolsJson(String v){toolsJson=v;}
    public Integer getBackground(){return background;} public void setBackground(Integer v){background=v;}
    public String getSummary(){return summary;} public void setSummary(String v){summary=v;}
    public LocalDateTime getCreateTime(){return createTime;} public void setCreateTime(LocalDateTime v){createTime=v;}
    public LocalDateTime getUpdateTime(){return updateTime;} public void setUpdateTime(LocalDateTime v){updateTime=v;}
    public String getParentToolCallId(){return parentToolCallId;} public void setParentToolCallId(String v){parentToolCallId=v;}
    public Long getChildTaskId(){return childTaskId;} public void setChildTaskId(Long v){childTaskId=v;}
    public Integer getSpawnDepth(){return spawnDepth;} public void setSpawnDepth(Integer v){spawnDepth=v;}
}
