package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value = "t_agent_project_config_proposal")
public class AgentProjectConfigProposal {
    @TableId(value = "proposal_id", type = IdType.AUTO)
    private Long proposalId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("proposal_key")
    private String proposalKey;
    @TableField("base_revision")
    private Long baseRevision;
    @TableField("candidate_config_digest")
    private String candidateConfigDigest;
    @TableField("patch_reference")
    private String patchReference;
    @TableField("changed_path_summary")
    private String changedPathSummary;
    @TableField("reason")
    private String reason;
    @TableField("origin_task_id")
    private Long originTaskId;
    @TableField("origin_execution_epoch")
    private Long originExecutionEpoch;
    @TableField("origin_tool_call_id")
    private String originToolCallId;
    @TableField("source")
    private String source;
    @TableField("creator")
    private String creator;
    @TableField("status")
    private String status;
    @TableField("expires_time")
    private LocalDateTime expiresTime;
    @TableField("decision_idempotency_key")
    private String decisionIdempotencyKey;
    @TableField("decision_actor")
    private String decisionActor;
    @TableField("decision_time")
    private LocalDateTime decisionTime;
    @TableField("applied_revision")
    private Long appliedRevision;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getProposalId() { return proposalId; }
    public void setProposalId(Long proposalId) { this.proposalId = proposalId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public String getProposalKey() { return proposalKey; }
    public void setProposalKey(String proposalKey) { this.proposalKey = proposalKey; }
    public Long getBaseRevision() { return baseRevision; }
    public void setBaseRevision(Long baseRevision) { this.baseRevision = baseRevision; }
    public String getCandidateConfigDigest() { return candidateConfigDigest; }
    public void setCandidateConfigDigest(String candidateConfigDigest) { this.candidateConfigDigest = candidateConfigDigest; }
    public String getPatchReference() { return patchReference; }
    public void setPatchReference(String patchReference) { this.patchReference = patchReference; }
    public String getChangedPathSummary() { return changedPathSummary; }
    public void setChangedPathSummary(String changedPathSummary) { this.changedPathSummary = changedPathSummary; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getOriginTaskId() { return originTaskId; }
    public void setOriginTaskId(Long originTaskId) { this.originTaskId = originTaskId; }
    public Long getOriginExecutionEpoch() { return originExecutionEpoch; }
    public void setOriginExecutionEpoch(Long originExecutionEpoch) { this.originExecutionEpoch = originExecutionEpoch; }
    public String getOriginToolCallId() { return originToolCallId; }
    public void setOriginToolCallId(String originToolCallId) { this.originToolCallId = originToolCallId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresTime() { return expiresTime; }
    public void setExpiresTime(LocalDateTime expiresTime) { this.expiresTime = expiresTime; }
    public String getDecisionIdempotencyKey() { return decisionIdempotencyKey; }
    public void setDecisionIdempotencyKey(String decisionIdempotencyKey) { this.decisionIdempotencyKey = decisionIdempotencyKey; }
    public String getDecisionActor() { return decisionActor; }
    public void setDecisionActor(String decisionActor) { this.decisionActor = decisionActor; }
    public LocalDateTime getDecisionTime() { return decisionTime; }
    public void setDecisionTime(LocalDateTime decisionTime) { this.decisionTime = decisionTime; }
    public Long getAppliedRevision() { return appliedRevision; }
    public void setAppliedRevision(Long appliedRevision) { this.appliedRevision = appliedRevision; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
