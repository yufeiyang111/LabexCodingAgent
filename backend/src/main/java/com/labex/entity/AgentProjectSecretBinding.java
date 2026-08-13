package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Durable metadata for one project-scoped secret binding. The secret value itself is never
 * stored; only the encrypted envelope produced by {@code SecretStore} is persisted, together
 * with the field ID, the deterministic alias and the configured flag.
 */
@TableName(value = "t_agent_project_secret_binding")
public class AgentProjectSecretBinding {
    @TableId(value = "binding_id", type = IdType.AUTO)
    private Long bindingId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("proposal_id")
    private Long proposalId;
    @TableField("field_id")
    private String fieldId;
    @TableField("alias")
    private String alias;
    @TableField("encrypted_value")
    private String encryptedValue;
    @TableField("key_version")
    private String keyVersion;
    @TableField("ttl_seconds")
    private Integer ttlSeconds;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("configured")
    private Integer configured;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public Long getProposalId() { return proposalId; }
    public void setProposalId(Long proposalId) { this.proposalId = proposalId; }
    public String getFieldId() { return fieldId; }
    public void setFieldId(String fieldId) { this.fieldId = fieldId; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getEncryptedValue() { return encryptedValue; }
    public void setEncryptedValue(String encryptedValue) { this.encryptedValue = encryptedValue; }
    public String getKeyVersion() { return keyVersion; }
    public void setKeyVersion(String keyVersion) { this.keyVersion = keyVersion; }
    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Integer getConfigured() { return configured; }
    public void setConfigured(Integer configured) { this.configured = configured; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
