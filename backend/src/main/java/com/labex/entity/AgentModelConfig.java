package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

@TableName(value = "t_agent_model_config")
public class AgentModelConfig {
    @TableId(value = "config_id", type = IdType.AUTO)
    private Integer configId;

    @TableField(value = "student_id")
    private Integer studentId;

    @TableField(value = "config_name")
    private String configName;

    @TableField(value = "provider")
    private String provider;

    @TableField(value = "model_name")
    private String modelName;

    @TableField(value = "api_key")
    private String apiKey;

    @TableField(value = "api_key_encrypted")
    private String apiKeyEncrypted;

    @TableField(value = "api_key_key_version")
    private String apiKeyKeyVersion;

    @TableField(exist = false)
    private String apiKeyMasked;

    @TableField(value = "base_url")
    private String baseUrl;

    @TableField(value = "max_tokens")
    private Integer maxTokens;

    @TableField(value = "context_window_tokens")
    private Integer contextWindowTokens;

    @TableField(value = "prompt_cache_key_enabled")
    private Integer promptCacheKeyEnabled;

    @TableField(value = "reasoning_effort")
    private String reasoningEffort;

    @TableField(value = "request_options_json")
    private String requestOptionsJson;

    @TableField(value = "image_input_enabled")
    private Integer imageInputEnabled;

    @TableField(value = "compaction_auto")
    private Integer compactionAuto;

    @TableField(value = "compaction_prune")
    private Integer compactionPrune;

    @TableField(value = "compaction_tail_turns")
    private Integer compactionTailTurns;

    @TableField(value = "compaction_preserve_recent_tokens")
    private Integer compactionPreserveRecentTokens;

    @TableField(value = "compaction_reserved_tokens")
    private Integer compactionReservedTokens;

    @TableField(value = "compaction_model_config_id")
    private Integer compactionModelConfigId;

    @TableField(value = "compaction_threshold_percent")
    private Integer compactionThresholdPercent;

    @TableField(value = "temperature")
    private Double temperature;

    @TableField(value = "is_default")
    private Integer isDefault;

    @TableField(value = "status")
    private Integer status;

    @TableField(value = "create_time")
    private LocalDateTime createTime;

    @TableField(value = "update_time")
    private LocalDateTime updateTime;

    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }

    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }

    public String getConfigName() { return configName; }
    public void setConfigName(String configName) { this.configName = configName; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    @JsonIgnore
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    @JsonIgnore
    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    @JsonIgnore
    public String getApiKeyKeyVersion() { return apiKeyKeyVersion; }
    public void setApiKeyKeyVersion(String apiKeyKeyVersion) { this.apiKeyKeyVersion = apiKeyKeyVersion; }

    @JsonProperty("apiKey")
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Integer getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Integer getPromptCacheKeyEnabled() { return promptCacheKeyEnabled; }
    public void setPromptCacheKeyEnabled(Integer promptCacheKeyEnabled) { this.promptCacheKeyEnabled = promptCacheKeyEnabled; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public String getRequestOptionsJson() { return requestOptionsJson; }
    public void setRequestOptionsJson(String requestOptionsJson) { this.requestOptionsJson = requestOptionsJson; }

    public Integer getImageInputEnabled() { return imageInputEnabled; }
    public void setImageInputEnabled(Integer imageInputEnabled) { this.imageInputEnabled = imageInputEnabled; }

    public Integer getCompactionAuto() { return compactionAuto; }
    public void setCompactionAuto(Integer compactionAuto) { this.compactionAuto = compactionAuto; }

    public Integer getCompactionPrune() { return compactionPrune; }
    public void setCompactionPrune(Integer compactionPrune) { this.compactionPrune = compactionPrune; }

    public Integer getCompactionTailTurns() { return compactionTailTurns; }
    public void setCompactionTailTurns(Integer compactionTailTurns) { this.compactionTailTurns = compactionTailTurns; }

    public Integer getCompactionPreserveRecentTokens() { return compactionPreserveRecentTokens; }
    public void setCompactionPreserveRecentTokens(Integer compactionPreserveRecentTokens) { this.compactionPreserveRecentTokens = compactionPreserveRecentTokens; }

    public Integer getCompactionReservedTokens() { return compactionReservedTokens; }
    public void setCompactionReservedTokens(Integer compactionReservedTokens) { this.compactionReservedTokens = compactionReservedTokens; }

    public Integer getCompactionModelConfigId() { return compactionModelConfigId; }
    public void setCompactionModelConfigId(Integer compactionModelConfigId) { this.compactionModelConfigId = compactionModelConfigId; }

    public Integer getCompactionThresholdPercent() { return compactionThresholdPercent; }
    public void setCompactionThresholdPercent(Integer compactionThresholdPercent) { this.compactionThresholdPercent = compactionThresholdPercent; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
