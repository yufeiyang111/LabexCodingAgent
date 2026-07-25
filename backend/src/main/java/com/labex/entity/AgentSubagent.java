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
    public Long getSubagentId(){return subagentId;} public void setSubagentId(Long v){subagentId=v;}
    public Long getTaskId(){return taskId;} public void setTaskId(Long v){taskId=v;}
    public Long getParentSubagentId(){return parentSubagentId;} public void setParentSubagentId(Long v){parentSubagentId=v;}
    public String getIdentity(){return identity;} public void setIdentity(String v){identity=v;}
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
}
