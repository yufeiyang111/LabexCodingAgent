package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value = "t_agent_run_config_snapshot")
public class AgentRunConfigSnapshot {
    @TableId(value = "snapshot_id", type = IdType.AUTO)
    private Long snapshotId;
    @TableField("task_id")
    private Long taskId;
    @TableField("execution_epoch")
    private Long executionEpoch;
    @TableField("project_id")
    private Integer projectId;
    @TableField("project_config_revision")
    private Long projectConfigRevision;
    @TableField("project_config_digest")
    private String projectConfigDigest;
    @TableField("effective_config_json")
    private String effectiveConfigJson;
    @TableField("effective_config_digest")
    private String effectiveConfigDigest;
    @TableField("model_fingerprint")
    private String modelFingerprint;
    @TableField("capability_digest")
    private String capabilityDigest;
    @TableField("resource_digest")
    private String resourceDigest;
    @TableField("runtime_profile")
    private String runtimeProfile;
    @TableField("network_policy_json")
    private String networkPolicyJson;
    @TableField("verification_policy_json")
    private String verificationPolicyJson;
    @TableField("environment_operation_ref")
    private String environmentOperationRef;
    @TableField("secret_aliases_json")
    private String secretAliasesJson;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getExecutionEpoch() { return executionEpoch; }
    public void setExecutionEpoch(Long executionEpoch) { this.executionEpoch = executionEpoch; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public Long getProjectConfigRevision() { return projectConfigRevision; }
    public void setProjectConfigRevision(Long projectConfigRevision) { this.projectConfigRevision = projectConfigRevision; }
    public String getProjectConfigDigest() { return projectConfigDigest; }
    public void setProjectConfigDigest(String projectConfigDigest) { this.projectConfigDigest = projectConfigDigest; }
    public String getEffectiveConfigJson() { return effectiveConfigJson; }
    public void setEffectiveConfigJson(String effectiveConfigJson) { this.effectiveConfigJson = effectiveConfigJson; }
    public String getEffectiveConfigDigest() { return effectiveConfigDigest; }
    public void setEffectiveConfigDigest(String effectiveConfigDigest) { this.effectiveConfigDigest = effectiveConfigDigest; }
    public String getModelFingerprint() { return modelFingerprint; }
    public void setModelFingerprint(String modelFingerprint) { this.modelFingerprint = modelFingerprint; }
    public String getCapabilityDigest() { return capabilityDigest; }
    public void setCapabilityDigest(String capabilityDigest) { this.capabilityDigest = capabilityDigest; }
    public String getResourceDigest() { return resourceDigest; }
    public void setResourceDigest(String resourceDigest) { this.resourceDigest = resourceDigest; }
    public String getRuntimeProfile() { return runtimeProfile; }
    public void setRuntimeProfile(String runtimeProfile) { this.runtimeProfile = runtimeProfile; }
    public String getNetworkPolicyJson() { return networkPolicyJson; }
    public void setNetworkPolicyJson(String networkPolicyJson) { this.networkPolicyJson = networkPolicyJson; }
    public String getVerificationPolicyJson() { return verificationPolicyJson; }
    public void setVerificationPolicyJson(String verificationPolicyJson) { this.verificationPolicyJson = verificationPolicyJson; }
    public String getEnvironmentOperationRef() { return environmentOperationRef; }
    public void setEnvironmentOperationRef(String environmentOperationRef) { this.environmentOperationRef = environmentOperationRef; }
    public String getSecretAliasesJson() { return secretAliasesJson; }
    public void setSecretAliasesJson(String secretAliasesJson) { this.secretAliasesJson = secretAliasesJson; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
