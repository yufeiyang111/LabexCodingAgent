package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value = "t_agent_project_config_audit_event")
public class AgentProjectConfigAuditEvent {
    @TableId(value = "event_id", type = IdType.AUTO)
    private Long eventId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("event_type")
    private String eventType;
    @TableField("actor")
    private String actor;
    @TableField("reason")
    private String reason;
    @TableField("previous_status")
    private String previousStatus;
    @TableField("next_status")
    private String nextStatus;
    @TableField("before_digest")
    private String beforeDigest;
    @TableField("after_digest")
    private String afterDigest;
    @TableField("changed_path_summary")
    private String changedPathSummary;
    @TableField("task_id")
    private Long taskId;
    @TableField("execution_epoch")
    private Long executionEpoch;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("create_time")
    private LocalDateTime createTime;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
    public String getNextStatus() { return nextStatus; }
    public void setNextStatus(String nextStatus) { this.nextStatus = nextStatus; }
    public String getBeforeDigest() { return beforeDigest; }
    public void setBeforeDigest(String beforeDigest) { this.beforeDigest = beforeDigest; }
    public String getAfterDigest() { return afterDigest; }
    public void setAfterDigest(String afterDigest) { this.afterDigest = afterDigest; }
    public String getChangedPathSummary() { return changedPathSummary; }
    public void setChangedPathSummary(String changedPathSummary) { this.changedPathSummary = changedPathSummary; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getExecutionEpoch() { return executionEpoch; }
    public void setExecutionEpoch(Long executionEpoch) { this.executionEpoch = executionEpoch; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
