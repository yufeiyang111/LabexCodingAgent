package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value = "t_agent_project_config_revision")
public class AgentProjectConfigRevision {
    @TableId(value = "revision_id", type = IdType.AUTO)
    private Long revisionId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("revision")
    private Long revision;
    @TableField("config_digest")
    private String configDigest;
    @TableField("tree_reference")
    private String treeReference;
    @TableField("schema_version")
    private String schemaVersion;
    @TableField("normalized_config")
    private String normalizedConfig;
    @TableField("validation_status")
    private String validationStatus;
    @TableField("source_actor")
    private String sourceActor;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getRevisionId() { return revisionId; }
    public void setRevisionId(Long revisionId) { this.revisionId = revisionId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getConfigDigest() { return configDigest; }
    public void setConfigDigest(String configDigest) { this.configDigest = configDigest; }
    public String getTreeReference() { return treeReference; }
    public void setTreeReference(String treeReference) { this.treeReference = treeReference; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getNormalizedConfig() { return normalizedConfig; }
    public void setNormalizedConfig(String normalizedConfig) { this.normalizedConfig = normalizedConfig; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public String getSourceActor() { return sourceActor; }
    public void setSourceActor(String sourceActor) { this.sourceActor = sourceActor; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
