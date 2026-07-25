package com.labex.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * A single-use, server-owned approval capability for one immutable command request.
 * Command text in this record must already be normalized and redacted by the command-security boundary.
 */
@TableName("t_command_approval")
public class CommandApproval {
    @TableId("approval_id")
    private String approvalId;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("task_id")
    private Long taskId;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("session_id")
    private String sessionId;
    @TableField("source")
    private String source;
    @TableField("invocation_id")
    private String invocationId;
    @TableField("tool_call_id")
    private String toolCallId;
    @TableField("command_digest")
    private String commandDigest;
    @TableField("canonical_command")
    private String canonicalCommand;
    @TableField("display_command")
    private String displayCommand;
    @TableField("working_directory")
    private String workingDirectory;
    @TableField("shell")
    private String shell;
    @TableField("command_options")
    private String commandOptions;
    @TableField("classification")
    private String classification;
    @TableField("policy_version")
    private String policyVersion;
    @TableField("status")
    private String status;
    @TableField("decision_idempotency_key")
    private String decisionIdempotencyKey;
    @TableField("decision_time")
    private LocalDateTime decisionTime;
    @TableField("decided_by_student_id")
    private Integer decidedByStudentId;
    @TableField("expires_time")
    private LocalDateTime expiresTime;
    @TableField("consumed_time")
    private LocalDateTime consumedTime;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
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
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String invocationId) { this.invocationId = invocationId; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getCommandDigest() { return commandDigest; }
    public void setCommandDigest(String commandDigest) { this.commandDigest = commandDigest; }
    public String getCanonicalCommand() { return canonicalCommand; }
    public void setCanonicalCommand(String canonicalCommand) { this.canonicalCommand = canonicalCommand; }
    public String getDisplayCommand() { return displayCommand; }
    public void setDisplayCommand(String displayCommand) { this.displayCommand = displayCommand; }
    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }
    public String getCommandOptions() { return commandOptions; }
    public void setCommandOptions(String commandOptions) { this.commandOptions = commandOptions; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecisionIdempotencyKey() { return decisionIdempotencyKey; }
    public void setDecisionIdempotencyKey(String decisionIdempotencyKey) { this.decisionIdempotencyKey = decisionIdempotencyKey; }
    public LocalDateTime getDecisionTime() { return decisionTime; }
    public void setDecisionTime(LocalDateTime decisionTime) { this.decisionTime = decisionTime; }
    public Integer getDecidedByStudentId() { return decidedByStudentId; }
    public void setDecidedByStudentId(Integer decidedByStudentId) { this.decidedByStudentId = decidedByStudentId; }
    public LocalDateTime getExpiresTime() { return expiresTime; }
    public void setExpiresTime(LocalDateTime expiresTime) { this.expiresTime = expiresTime; }
    public LocalDateTime getConsumedTime() { return consumedTime; }
    public void setConsumedTime(LocalDateTime consumedTime) { this.consumedTime = consumedTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
