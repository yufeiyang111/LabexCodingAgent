package com.labex.labexagent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 单轮模型调用的外层 watchdog 配置；不替代 provider 的连接、首包或流空闲超时。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.model-turn")
public class AgentModelTurnProperties {
    public static final long DEFAULT_TOTAL_TIMEOUT_MS = 300_000L;

    /** 0 表示关闭外层总时限，仍由 provider 自身 timeout 保证请求可取消。 */
    private long totalTimeoutMs = DEFAULT_TOTAL_TIMEOUT_MS;

    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    public void setTotalTimeoutMs(long value) {
        totalTimeoutMs = Math.max(0L, value);
    }
}