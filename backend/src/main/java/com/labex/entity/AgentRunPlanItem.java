package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Agent 任务的持久化有序计划项。 */
@TableName("t_agent_run_plan_item")
public class AgentRunPlanItem {
    @TableId(value = "plan_item_id", type = IdType.AUTO)
    private Long planItemId;
    @TableField("task_id")
    private Long taskId;
    @TableField("execution_epoch")
    private Long executionEpoch;
    @TableField("plan_revision")
    private Long planRevision;
    @TableField("position")
    private Integer position;
    @TableField("title")
    private String title;
    @TableField("description")
    private String description;
    @TableField("status")
    private String status;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getPlanItemId() { return planItemId; }
    public void setPlanItemId(Long planItemId) { this.planItemId = planItemId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getExecutionEpoch() { return executionEpoch; }
    public void setExecutionEpoch(Long executionEpoch) { this.executionEpoch = executionEpoch; }
    public Long getPlanRevision() { return planRevision; }
    public void setPlanRevision(Long planRevision) { this.planRevision = planRevision; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
