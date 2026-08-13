package com.labex.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("t_agent_run_interaction")
public class AgentRunInteraction {
    /** 阻塞任务等待配置 proposal 决策的交互类型；proposal 表仍为权威事实。 */
    public static final String TYPE_CONFIG_PROPOSAL = "config_proposal";
    /** config_proposal 的合法已解决状态：owner 批准。 */
    public static final String STATUS_APPROVED = "approved";
    /** config_proposal 的合法已解决状态：owner 拒绝（或 proposal 以非成功终态结束）。 */
    public static final String STATUS_REJECTED = "rejected";

    @TableId("interaction_id")
    private String interactionId;
    @TableField("task_id")
    private Long taskId;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("session_id")
    private String sessionId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("interaction_type")
    private String interactionType;
    @TableField("status")
    private String status;
    @TableField("request_payload")
    private String requestPayload;
    @TableField("response_payload")
    private String responsePayload;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("expires_time")
    private LocalDateTime expiresTime;
    @TableField("resume_claim_id")
    private String resumeClaimId;
    @TableField("resume_claim_epoch")
    private Long resumeClaimEpoch;
    @TableField("resume_claimed_at")
    private LocalDateTime resumeClaimedAt;
    @TableField("resume_consumed_at")
    private LocalDateTime resumeConsumedAt;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public String getInteractionId() { return interactionId; }
    public void setInteractionId(String interactionId) { this.interactionId = interactionId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String requestPayload) { this.requestPayload = requestPayload; }
    public String getResponsePayload() { return responsePayload; }
    public void setResponsePayload(String responsePayload) { this.responsePayload = responsePayload; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getExpiresTime() { return expiresTime; }
    public void setExpiresTime(LocalDateTime expiresTime) { this.expiresTime = expiresTime; }
    public String getResumeClaimId() { return resumeClaimId; }
    public void setResumeClaimId(String resumeClaimId) { this.resumeClaimId = resumeClaimId; }
    public Long getResumeClaimEpoch() { return resumeClaimEpoch; }
    public void setResumeClaimEpoch(Long resumeClaimEpoch) { this.resumeClaimEpoch = resumeClaimEpoch; }
    public LocalDateTime getResumeClaimedAt() { return resumeClaimedAt; }
    public void setResumeClaimedAt(LocalDateTime resumeClaimedAt) { this.resumeClaimedAt = resumeClaimedAt; }
    public LocalDateTime getResumeConsumedAt() { return resumeConsumedAt; }
    public void setResumeConsumedAt(LocalDateTime resumeConsumedAt) { this.resumeConsumedAt = resumeConsumedAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
