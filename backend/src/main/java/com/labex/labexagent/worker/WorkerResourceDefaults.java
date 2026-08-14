package com.labex.labexagent.worker;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把 Worker 资源默认值从硬编码改成显式配置。静态暴露是刻意选择：
 * {@link WorkerRunSpec#forWorkspace} 是遍布各 Tool 的无状态工厂入口，
 * 在无 Spring 上下文的测试中回退到 {@link WorkerPolicy#fallbackDefaults()}。
 */
@Component
public final class WorkerResourceDefaults implements InitializingBean {

    @Value("${labex-agent.worker.resources.cpu-millis:1000}")
    private int cpuMillis;

    @Value("${labex-agent.worker.resources.memory-megabytes:1024}")
    private int memoryMegabytes;

    @Value("${labex-agent.worker.resources.max-pids:256}")
    private int maxPids;

    @Value("${labex-agent.worker.docker.image:}")
    private String configuredImage;

    @Value("${labex-agent.worker.network-default-enabled:true}")
    private boolean networkDefaultEnabled;

    private static volatile WorkerPolicy configuredDefaults;

    /** 当前配置的默认策略；Spring 上下文未初始化（纯单元测试）时回退硬编码。 */
    public static WorkerPolicy defaults() {
        WorkerPolicy policy = configuredDefaults;
        return policy != null ? policy : WorkerPolicy.fallbackDefaults();
    }

    @Override
    public void afterPropertiesSet() {
        String image = configuredImage == null || configuredImage.isBlank()
                ? "labex-agent-sandbox:latest"
                : configuredImage.trim();
        configuredDefaults = new WorkerPolicy(image, Math.max(100, cpuMillis),
                Math.max(128, memoryMegabytes), Math.max(16, maxPids), networkDefaultEnabled);
    }
}
