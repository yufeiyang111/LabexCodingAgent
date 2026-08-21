package com.labex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.llm.ReasoningEffort;
import com.labex.labexagent.secret.SecretStore;
import com.labex.mapper.AgentModelConfigMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentModelConfigService extends ServiceImpl<AgentModelConfigMapper, AgentModelConfig> {
    public static final int DEFAULT_MAX_TOKENS = 32_000;

    private final SecretStore secretStore;

    public AgentModelConfigService(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    public List<AgentModelConfig> listByStudent(Integer studentId) {
        return this.list(new LambdaQueryWrapper<AgentModelConfig>()
                .eq(AgentModelConfig::getStudentId, studentId)
                .eq(AgentModelConfig::getStatus, 1)
                .orderByDesc(AgentModelConfig::getIsDefault)
                .orderByDesc(AgentModelConfig::getUpdateTime));
    }

    /** 只读统计：全局启用的模型配置数量（健康检查等运维只读场景使用）。 */
    public long countEnabled() {
        return this.lambdaQuery().eq(AgentModelConfig::getStatus, 1).count();
    }

    /** 只读查询：按 provider 匹配的启用模型配置 id 列表（运维运行态按 Provider 过滤使用）。 */
    public List<Integer> listConfigIdsByProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return List.of();
        }
        return this.lambdaQuery()
                .eq(AgentModelConfig::getProvider, provider.trim())
                .eq(AgentModelConfig::getStatus, 1)
                .list()
                .stream()
                .map(AgentModelConfig::getConfigId)
                .collect(java.util.stream.Collectors.toList());
    }

    public AgentModelConfig getDefault(Integer studentId) {
        return this.lambdaQuery()
                .eq(AgentModelConfig::getStudentId, studentId)
                .eq(AgentModelConfig::getIsDefault, 1)
                .eq(AgentModelConfig::getStatus, 1)
                .one();
    }

    public AgentModelConfig getOwned(Integer studentId, Integer configId) {
        return this.lambdaQuery()
                .eq(AgentModelConfig::getConfigId, configId)
                .eq(AgentModelConfig::getStudentId, studentId)
                .one();
    }

    /**
     * 恢复路径的精确解析：只接受 task 持久化选定的、仍归属该用户且启用的配置。
     * 缺失、禁用或未持久化一律返回 null，调用方必须 fail closed，绝不能回退到当前默认配置。
     */
    public AgentModelConfig resolveOwnedEnabled(Integer studentId, Integer configId) {
        if (configId == null) {
            return null;
        }
        AgentModelConfig config = this.getOwned(studentId, configId);
        if (config == null || !Integer.valueOf(1).equals(config.getStatus())) {
            return null;
        }
        return config;
    }

    public AgentModelConfig create(Integer studentId, String configName, String provider,
                                     String modelName, String apiKey, String baseUrl,
                                     Integer maxTokens, Double temperature, boolean makeDefault) {
        return create(studentId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, null, temperature, makeDefault);
    }

    public AgentModelConfig create(Integer studentId, String configName, String provider,
                                     String modelName, String apiKey, String baseUrl,
                                     Integer maxTokens, Integer contextWindowTokens,
                                     Double temperature, boolean makeDefault) {
        return create(studentId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, contextWindowTokens, temperature, makeDefault, false);
    }

    public AgentModelConfig create(Integer studentId, String configName, String provider,
                                     String modelName, String apiKey, String baseUrl,
                                     Integer maxTokens, Integer contextWindowTokens,
                                     Double temperature, boolean makeDefault, boolean promptCacheKeyEnabled) {
        int effectiveMaxTokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        validateTokenLimits(effectiveMaxTokens, contextWindowTokens);

        if (makeDefault) {
            this.lambdaUpdate()
                    .eq(AgentModelConfig::getStudentId, studentId)
                    .eq(AgentModelConfig::getIsDefault, 1)
                    .set(AgentModelConfig::getIsDefault, 0)
                    .update();
        }

        AgentModelConfig config = new AgentModelConfig();
        config.setStudentId(studentId);
        config.setConfigName(configName);
        config.setProvider(provider);
        config.setModelName(modelName);
        storeApiKey(config, apiKey);
        config.setBaseUrl(baseUrl);
        config.setMaxTokens(effectiveMaxTokens);
        config.setContextWindowTokens(contextWindowTokens);
        config.setPromptCacheKeyEnabled(promptCacheKeyEnabled ? 1 : 0);
        config.setReasoningEffort("medium");
        config.setImageInputEnabled(0);
        config.setCompactionAuto(1);
        config.setCompactionPrune(0);
        config.setCompactionTailTurns(2);
        config.setCompactionThresholdPercent(90);
        config.setTemperature(temperature);
        config.setIsDefault(makeDefault ? 1 : 0);
        config.setStatus(1);
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(LocalDateTime.now());
        this.save(config);
        return config;
    }

    public AgentModelConfig update(Integer studentId, Integer configId, String configName,
                                    String provider, String modelName, String apiKey,
                                    String baseUrl, Integer maxTokens, Double temperature,
                                    Boolean makeDefault) {
        return update(studentId, configId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, null, temperature, makeDefault);
    }

    public AgentModelConfig update(Integer studentId, Integer configId, String configName,
                                    String provider, String modelName, String apiKey,
                                    String baseUrl, Integer maxTokens, Integer contextWindowTokens,
                                    Double temperature, Boolean makeDefault) {
        return update(studentId, configId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, contextWindowTokens, temperature, makeDefault, null);
    }

    public AgentModelConfig update(Integer studentId, Integer configId, String configName,
                                    String provider, String modelName, String apiKey,
                                    String baseUrl, Integer maxTokens, Integer contextWindowTokens,
                                    Double temperature, Boolean makeDefault, Boolean promptCacheKeyEnabled) {
        AgentModelConfig config = this.getOwned(studentId, configId);
        if (config == null) {
            throw new IllegalArgumentException("Config not found");
        }

        Integer effectiveMaxTokens = maxTokens != null ? maxTokens : config.getMaxTokens();
        Integer effectiveContextWindowTokens = contextWindowTokens != null
                ? contextWindowTokens
                : config.getContextWindowTokens();
        validateTokenLimits(effectiveMaxTokens, effectiveContextWindowTokens);
        validateCompactionLimits(effectiveMaxTokens, effectiveContextWindowTokens,
                config.getCompactionTailTurns(), config.getCompactionPreserveRecentTokens(),
                config.getCompactionReservedTokens());

        if (Boolean.TRUE.equals(makeDefault)) {
            this.lambdaUpdate()
                    .eq(AgentModelConfig::getStudentId, studentId)
                    .eq(AgentModelConfig::getIsDefault, 1)
                    .ne(AgentModelConfig::getConfigId, configId)
                    .set(AgentModelConfig::getIsDefault, 0)
                    .update();
        }

        if (configName != null) config.setConfigName(configName);
        if (provider != null) config.setProvider(provider);
        if (modelName != null) config.setModelName(modelName);
        if (apiKey != null && !apiKey.isBlank()) storeApiKey(config, apiKey);
        if (baseUrl != null) config.setBaseUrl(baseUrl);
        if (maxTokens != null) config.setMaxTokens(maxTokens);
        if (contextWindowTokens != null) config.setContextWindowTokens(contextWindowTokens);
        if (promptCacheKeyEnabled != null) config.setPromptCacheKeyEnabled(promptCacheKeyEnabled ? 1 : 0);
        if (temperature != null) config.setTemperature(temperature);
        if (makeDefault != null) config.setIsDefault(makeDefault ? 1 : 0);
        config.setUpdateTime(LocalDateTime.now());
        this.updateById(config);
        return config;
    }

    @Transactional
    public AgentModelConfig createWithCompactionPolicy(Integer studentId, String configName, String provider,
                                                        String modelName, String apiKey, String baseUrl,
                                                        Integer maxTokens, Integer contextWindowTokens,
                                                        Double temperature, boolean makeDefault,
                                                        boolean promptCacheKeyEnabled, Boolean compactionAuto,
                                                        Boolean compactionPrune, Integer compactionTailTurns,
                                                        Integer compactionPreserveRecentTokens,
                                                        Integer compactionReservedTokens) {
        return createWithCompactionPolicy(studentId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, contextWindowTokens, temperature, makeDefault, promptCacheKeyEnabled,
                compactionAuto, compactionPrune, compactionTailTurns, compactionPreserveRecentTokens,
                compactionReservedTokens, null);
    }

    @Transactional
    public AgentModelConfig createWithCompactionPolicy(Integer studentId, String configName, String provider,
                                                        String modelName, String apiKey, String baseUrl,
                                                        Integer maxTokens, Integer contextWindowTokens,
                                                        Double temperature, boolean makeDefault,
                                                        boolean promptCacheKeyEnabled, Boolean compactionAuto,
                                                        Boolean compactionPrune, Integer compactionTailTurns,
                                                        Integer compactionPreserveRecentTokens,
                                                        Integer compactionReservedTokens,
                                                        Integer compactionModelConfigId) {
        int effectiveMaxTokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        validateTokenLimits(effectiveMaxTokens, contextWindowTokens);
        validateCompactionLimits(effectiveMaxTokens, contextWindowTokens, compactionTailTurns,
                compactionPreserveRecentTokens, compactionReservedTokens);
        Integer normalizedCompactionModelConfigId = normalizeCompactionModelConfigId(compactionModelConfigId);
        validateCompactionModelReference(studentId, normalizedCompactionModelConfigId);
        AgentModelConfig config = create(studentId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, contextWindowTokens, temperature, makeDefault, promptCacheKeyEnabled);
        return applyCompactionPolicy(config, compactionAuto, compactionPrune, compactionTailTurns,
                compactionPreserveRecentTokens, compactionReservedTokens, normalizedCompactionModelConfigId, true);
    }

    @Transactional
    public AgentModelConfig updateWithCompactionPolicy(Integer studentId, Integer configId, String configName,
                                                        String provider, String modelName, String apiKey,
                                                        String baseUrl, Integer maxTokens, Integer contextWindowTokens,
                                                        Double temperature, Boolean makeDefault,
                                                        Boolean promptCacheKeyEnabled, Boolean compactionAuto,
                                                        Boolean compactionPrune, Integer compactionTailTurns,
                                                        Integer compactionPreserveRecentTokens,
                                                        Integer compactionReservedTokens) {
        return updateWithCompactionPolicy(studentId, configId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, contextWindowTokens, temperature, makeDefault, promptCacheKeyEnabled,
                compactionAuto, compactionPrune, compactionTailTurns, compactionPreserveRecentTokens,
                compactionReservedTokens, null, false);
    }

    @Transactional
    public AgentModelConfig updateWithCompactionPolicy(Integer studentId, Integer configId, String configName,
                                                        String provider, String modelName, String apiKey,
                                                        String baseUrl, Integer maxTokens, Integer contextWindowTokens,
                                                        Double temperature, Boolean makeDefault,
                                                        Boolean promptCacheKeyEnabled, Boolean compactionAuto,
                                                        Boolean compactionPrune, Integer compactionTailTurns,
                                                        Integer compactionPreserveRecentTokens,
                                                        Integer compactionReservedTokens,
                                                        Integer compactionModelConfigId,
                                                        boolean compactionModelConfigProvided) {
        AgentModelConfig existing = getOwned(studentId, configId);
        if (existing == null) {
            throw new IllegalArgumentException("Config not found");
        }
        Integer effectiveMaxTokens = maxTokens != null ? maxTokens : existing.getMaxTokens();
        Integer effectiveContextWindowTokens = contextWindowTokens != null
                ? contextWindowTokens : existing.getContextWindowTokens();
        validateTokenLimits(effectiveMaxTokens, effectiveContextWindowTokens);
        validateCompactionLimits(effectiveMaxTokens, effectiveContextWindowTokens,
                compactionTailTurns != null ? compactionTailTurns : existing.getCompactionTailTurns(),
                compactionPreserveRecentTokens != null ? compactionPreserveRecentTokens : existing.getCompactionPreserveRecentTokens(),
                compactionReservedTokens != null ? compactionReservedTokens : existing.getCompactionReservedTokens());
        Integer normalizedCompactionModelConfigId = normalizeCompactionModelConfigId(compactionModelConfigId);
        if (compactionModelConfigProvided) {
            validateCompactionModelReference(studentId, normalizedCompactionModelConfigId);
        }
        AgentModelConfig config = update(studentId, configId, configName, provider, modelName, apiKey, baseUrl,
                maxTokens, contextWindowTokens, temperature, makeDefault, promptCacheKeyEnabled);
        return applyCompactionPolicy(config, compactionAuto, compactionPrune, compactionTailTurns,
                compactionPreserveRecentTokens, compactionReservedTokens, normalizedCompactionModelConfigId,
                compactionModelConfigProvided);
    }

    private AgentModelConfig applyCompactionPolicy(AgentModelConfig config, Boolean compactionAuto,
                                                    Boolean compactionPrune, Integer compactionTailTurns,
                                                    Integer compactionPreserveRecentTokens,
                                                    Integer compactionReservedTokens,
                                                    Integer compactionModelConfigId,
                                                    boolean compactionModelConfigProvided) {
        if (compactionAuto != null) config.setCompactionAuto(compactionAuto ? 1 : 0);
        if (compactionPrune != null) config.setCompactionPrune(compactionPrune ? 1 : 0);
        if (compactionTailTurns != null) config.setCompactionTailTurns(compactionTailTurns);
        if (compactionPreserveRecentTokens != null) config.setCompactionPreserveRecentTokens(compactionPreserveRecentTokens);
        if (compactionReservedTokens != null) config.setCompactionReservedTokens(compactionReservedTokens);
        if (compactionModelConfigProvided) config.setCompactionModelConfigId(compactionModelConfigId);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);
        return config;
    }

    private Integer normalizeCompactionModelConfigId(Integer compactionModelConfigId) {
        return compactionModelConfigId == null || compactionModelConfigId <= 0 ? null : compactionModelConfigId;
    }

    private void validateCompactionModelReference(Integer studentId, Integer compactionModelConfigId) {
        if (compactionModelConfigId == null) {
            return;
        }
        AgentModelConfig referencedConfig = getOwned(studentId, compactionModelConfigId);
        if (referencedConfig == null || !Integer.valueOf(1).equals(referencedConfig.getStatus())) {
            throw new IllegalArgumentException("Compaction model config not found or disabled");
        }
    }

    private void validateCompactionLimits(Integer maxTokens, Integer contextWindowTokens,
                                          Integer tailTurns, Integer preserveRecentTokens,
                                          Integer reservedTokens) {
        if (tailTurns != null && tailTurns <= 0) {
            throw new IllegalArgumentException("compactionTailTurns must be greater than 0");
        }
        if (preserveRecentTokens != null && preserveRecentTokens <= 0) {
            throw new IllegalArgumentException("compactionPreserveRecentTokens must be greater than 0");
        }
        if (reservedTokens != null && reservedTokens < 0) {
            throw new IllegalArgumentException("compactionReservedTokens must not be negative");
        }
        if (contextWindowTokens == null || maxTokens == null) {
            return;
        }
        int inputCapacity = contextWindowTokens - maxTokens;
        if (inputCapacity <= 0) {
            return;
        }
        if (reservedTokens != null && reservedTokens >= inputCapacity) {
            throw new IllegalArgumentException("compactionReservedTokens must be smaller than the input capacity");
        }
        int usable = inputCapacity - (reservedTokens == null ? 0 : reservedTokens);
        if (preserveRecentTokens != null && preserveRecentTokens > usable) {
            throw new IllegalArgumentException("compactionPreserveRecentTokens must fit inside the usable input capacity");
        }
    }

    private void validateTokenLimits(Integer maxTokens, Integer contextWindowTokens) {
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be greater than 0");
        }
        if (contextWindowTokens != null && contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be greater than 0");
        }
        if (maxTokens != null && contextWindowTokens != null && contextWindowTokens <= maxTokens) {
            throw new IllegalArgumentException("contextWindowTokens must be greater than maxTokens");
        }
    }

    public AgentModelConfig updateCapabilities(Integer studentId, Integer configId, String reasoningEffort,
                                                    Boolean imageInputEnabled) {
        AgentModelConfig config = this.getOwned(studentId, configId);
        if (config == null) {
            throw new IllegalArgumentException("Config not found");
        }
        if (reasoningEffort != null) {
            config.setReasoningEffort(ReasoningEffort.normalize(reasoningEffort));
        } else if (config.getReasoningEffort() == null || config.getReasoningEffort().isBlank()) {
            config.setReasoningEffort("medium");
        }
        if (imageInputEnabled != null) {
            config.setImageInputEnabled(imageInputEnabled ? 1 : 0);
        } else if (config.getImageInputEnabled() == null) {
            config.setImageInputEnabled(0);
        }
        config.setUpdateTime(LocalDateTime.now());
        this.updateById(config);
        return config;
    }

    public void delete(Integer studentId, Integer configId) {
        AgentModelConfig config = this.getOwned(studentId, configId);
        if (config == null) throw new IllegalArgumentException("Config not found");
        config.setStatus(0);
        config.setUpdateTime(LocalDateTime.now());
        this.updateById(config);
    }

    public AgentModelConfig resolveForStudent(Integer studentId, Integer configId) {
        if (configId != null) {
            // 显式选定的配置必须精确解析：删除/缺失时 fail closed，不能静默回退到当前默认。
            return this.getOwned(studentId, configId);
        }
        AgentModelConfig defaultConfig = this.getDefault(studentId);
        return defaultConfig != null ? defaultConfig : null;
    }

    @Transactional
    public AgentModelConfig updateCompactionThreshold(Integer studentId, Integer configId, Integer thresholdPercent) {
        validateCompactionThreshold(thresholdPercent);
        AgentModelConfig config = getOwned(studentId, configId);
        if (config == null) {
            throw new IllegalArgumentException("Config not found");
        }
        config.setCompactionThresholdPercent(thresholdPercent);
        config.setUpdateTime(LocalDateTime.now());
        updateById(config);
        return config;
    }

    private void validateCompactionThreshold(Integer thresholdPercent) {
        if (thresholdPercent != null && (thresholdPercent < 70 || thresholdPercent > 99)) {
            throw new IllegalArgumentException("compactionThresholdPercent must be between 70 and 99");
        }
    }

    public boolean hasStoredApiKey(AgentModelConfig config) {
        return config != null && ((config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank())
                || (config.getApiKey() != null && !config.getApiKey().isBlank()));
    }

    public String resolveApiKey(AgentModelConfig config) {
        if (config == null) {
            return "";
        }
        if (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank()) {
            try (SecretStore.SecretLease lease = secretStore.open(
                    SecretStore.SecretScope.MODEL_API_KEY, config.getApiKeyEncrypted())) {
                return lease.value();
            }
        }
        return config.getApiKey() == null ? "" : config.getApiKey();
    }

    public int migrateLegacySecrets() {
        int migrated = 0;
        for (AgentModelConfig config : this.list()) {
            if ((config.getApiKeyEncrypted() == null || config.getApiKeyEncrypted().isBlank())
                    && config.getApiKey() != null && !config.getApiKey().isBlank()) {
                storeApiKey(config, config.getApiKey());
                config.setUpdateTime(LocalDateTime.now());
                this.updateById(config);
                migrated++;
            }
        }
        return migrated;
    }

    private void storeApiKey(AgentModelConfig config, String apiKey) {
        config.setApiKey("");
        config.setApiKeyEncrypted(null);
        config.setApiKeyKeyVersion(null);
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        SecretStore.StoredSecret stored = secretStore.store(SecretStore.SecretScope.MODEL_API_KEY, apiKey.trim());
        config.setApiKeyEncrypted(stored.ciphertext());
        config.setApiKeyKeyVersion(stored.keyVersion());
    }
}
