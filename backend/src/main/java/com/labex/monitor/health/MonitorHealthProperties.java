package com.labex.monitor.health;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运维健康检查模块可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor.health")
public class MonitorHealthProperties {
    /** 单个依赖检查的超时上限（毫秒），超时按 DOWN/TIMEOUT 处理且不影响其它依赖。 */
    private long timeoutMs = 3000;
    /** 并行执行依赖检查的线程池大小。 */
    private int executorPoolSize = 4;
    /** Worker 沙箱只读探活命令的超时上限（毫秒）。 */
    private long probeTimeoutMs = 5000;
}
