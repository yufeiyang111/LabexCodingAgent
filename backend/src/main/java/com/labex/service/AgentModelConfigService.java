package com.labex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.labex.entity.AgentModelConfig;
import com.labex.mapper.AgentModelConfigMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentModelConfigService extends ServiceImpl<AgentModelConfigMapper, AgentModelConfig> {

    public List<AgentModelConfig> listByStudent(Integer studentId) {
        return this.list(new LambdaQueryWrapper<AgentModelConfig>()
                .eq(AgentModelConfig::getStudentId, studentId)
                .eq(AgentModelConfig::getStatus, 1)
                .orderByDesc(AgentModelConfig::getIsDefault)
                .orderByDesc(AgentModelConfig::getUpdateTime));
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

    public AgentModelConfig create(Integer studentId, String configName, String provider,
                                    String modelName, String apiKey, String baseUrl,
                                    Integer maxTokens, Double temperature, boolean makeDefault) {
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
        config.setApiKey(apiKey);
        config.setBaseUrl(baseUrl);
        config.setMaxTokens(maxTokens != null ? maxTokens : 32768);
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
        AgentModelConfig config = this.getOwned(studentId, configId);
        if (config == null) throw new IllegalArgumentException("Config not found");

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
        if (apiKey != null && !apiKey.isBlank()) config.setApiKey(apiKey);
        if (baseUrl != null) config.setBaseUrl(baseUrl);
        if (maxTokens != null) config.setMaxTokens(maxTokens);
        if (temperature != null) config.setTemperature(temperature);
        if (makeDefault != null) config.setIsDefault(makeDefault ? 1 : 0);
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
            AgentModelConfig config = this.getOwned(studentId, configId);
            if (config != null) return config;
        }
        AgentModelConfig defaultConfig = this.getDefault(studentId);
        if (defaultConfig != null) return defaultConfig;
        return null;
    }
}
