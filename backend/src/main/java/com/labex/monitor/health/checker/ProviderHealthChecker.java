package com.labex.monitor.health.checker;

import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.monitor.health.HealthCheck;
import com.labex.monitor.health.HealthCheckResult;
import com.labex.rag.config.RagConfig;
import com.labex.service.AgentModelConfigService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Provider 健康检查：只读判断模型 Provider 基础设施与默认模型配置是否就绪。
 * 不发起任何真实模型调用，不读取/输出任何 API Key。
 */
@Component
public class ProviderHealthChecker implements HealthCheck {

    public static final String NAME = "provider";
    private static final Logger log = LoggerFactory.getLogger(ProviderHealthChecker.class);

    private final LlmProviderFactory providerFactory;
    private final AgentModelConfigService modelConfigService;
    private final RagConfig ragConfig;

    public ProviderHealthChecker(LlmProviderFactory providerFactory, AgentModelConfigService modelConfigService,
                                 RagConfig ragConfig) {
        this.providerFactory = providerFactory;
        this.modelConfigService = modelConfigService;
        this.ragConfig = ragConfig;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean affectsCoreService() {
        return true;
    }

    @Override
    public HealthCheckResult check() {
        long start = System.nanoTime();
        try {
            Map<String, Object> providers = providerFactory.getProviderInfo();
            if (providers == null || providers.isEmpty()) {
                return HealthCheckResult.down(NAME, true, latency(start),
                        "未检测到可用的模型 Provider", "NO_PROVIDER", Map.of());
            }
            long enabledConfigs = modelConfigService.countEnabled();
            String defaultKey = ragConfig.getMiniMaxApiKey();
            boolean hasDefaultKey = defaultKey != null && !defaultKey.isBlank();
            Map<String, Object> details = Map.of("providers", providers.size(), "enabledConfigs", enabledConfigs);
            if (enabledConfigs == 0 && !hasDefaultKey) {
                return HealthCheckResult.degraded(NAME, true, latency(start),
                        "未配置默认模型，Agent 对话暂不可用", "NOT_CONFIGURED", details);
            }
            return HealthCheckResult.up(NAME, true, latency(start), "模型 Provider 可用", details);
        } catch (Exception e) {
            log.warn("provider health check failed: {}", e.getClass().getSimpleName());
            return HealthCheckResult.down(NAME, true, latency(start), "模型 Provider 检查失败", "CHECK_FAILED", Map.of());
        }
    }

    private static long latency(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
