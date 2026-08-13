package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value = "t_agent_project_config_external_change")
public class AgentProjectConfigExternalChange {
    @TableId(value = "external_change_id", type = IdType.AUTO)
    private Long externalChangeId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("base_revision")
    private Long baseRevision;
    @TableField("observed_tree_digest")
    private String observedTreeDigest;
    @TableField("changed_path_summary")
    private String changedPathSummary;
    @TableField("status")
    private String status;
    @TableField("proposal_id")
    private Long proposalId;
    @TableField("detected_at")
    private LocalDateTime detectedAt;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getExternalChangeId() { return externalChangeId; }
    public void setExternalChangeId(Long externalChangeId) { this.externalChangeId = externalChangeId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public Long getBaseRevision() { return baseRevision; }
    public void setBaseRevision(Long baseRevision) { this.baseRevision = baseRevision; }
    public String getObservedTreeDigest() { return observedTreeDigest; }
    public void setObservedTreeDigest(String observedTreeDigest) { this.observedTreeDigest = observedTreeDigest; }
    public String getChangedPathSummary() { return changedPathSummary; }
    public void setChangedPathSummary(String changedPathSummary) { this.changedPathSummary = changedPathSummary; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getProposalId() { return proposalId; }
    public void setProposalId(Long proposalId) { this.proposalId = proposalId; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
