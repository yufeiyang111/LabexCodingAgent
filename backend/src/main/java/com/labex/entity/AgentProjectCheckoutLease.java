package com.labex.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Durable exclusive lease for one project checkout during an active agent execution segment. */
@TableName("t_agent_project_checkout_lease")
public class AgentProjectCheckoutLease {
    @TableId("checkout_key")
    private String checkoutKey;
    @TableField("project_id")
    private Integer projectId;
    @TableField("workspace_path")
    private String workspacePath;
    @TableField("task_id")
    private Long taskId;
    @TableField("lease_owner")
    private String leaseOwner;
    @TableField("lease_epoch")
    private Long leaseEpoch;
    @TableField("lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    @TableField("heartbeat_at")
    private LocalDateTime heartbeatAt;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public String getCheckoutKey() { return checkoutKey; }
    public void setCheckoutKey(String checkoutKey) { this.checkoutKey = checkoutKey; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Long getLeaseEpoch() { return leaseEpoch; }
    public void setLeaseEpoch(Long leaseEpoch) { this.leaseEpoch = leaseEpoch; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(LocalDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
