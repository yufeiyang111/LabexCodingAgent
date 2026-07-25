package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Append-only, metadata-only audit record for a command approval capability. */
@TableName("t_command_audit_event")
public class CommandAuditEvent {
    @TableId(value = "event_id", type = IdType.AUTO)
    private Long eventId;
    @TableField("approval_id") private String approvalId;
    @TableField("student_id") private Integer studentId;
    @TableField("project_id") private Integer projectId;
    @TableField("task_id") private Long taskId;
    @TableField("conversation_id") private String conversationId;
    @TableField("session_id") private String sessionId;
    @TableField("event_type") private String eventType;
    @TableField("source") private String source;
    @TableField("invocation_id") private String invocationId;
    @TableField("tool_call_id") private String toolCallId;
    @TableField("command_digest") private String commandDigest;
    @TableField("classification") private String classification;
    @TableField("policy_version") private String policyVersion;
    @TableField("decision") private String decision;
    @TableField("execution_status") private String executionStatus;
    @TableField("exit_code") private Integer exitCode;
    @TableField("duration_ms") private Long durationMs;
    @TableField("output_digest") private String outputDigest;
    @TableField("output_size_bytes") private Long outputSizeBytes;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("create_time") private LocalDateTime createTime;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String invocationId) { this.invocationId = invocationId; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getCommandDigest() { return commandDigest; }
    public void setCommandDigest(String commandDigest) { this.commandDigest = commandDigest; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getOutputDigest() { return outputDigest; }
    public void setOutputDigest(String outputDigest) { this.outputDigest = outputDigest; }
    public Long getOutputSizeBytes() { return outputSizeBytes; }
    public void setOutputSizeBytes(Long outputSizeBytes) { this.outputSizeBytes = outputSizeBytes; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
