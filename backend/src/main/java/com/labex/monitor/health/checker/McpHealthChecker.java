package com.labex.monitor.health.checker;

import com.labex.monitor.health.HealthCheck;
import com.labex.monitor.health.HealthCheckResult;
import com.labex.service.AgentMcpServerService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** MCP 健康检查：只读统计已启用的 MCP Server 配置，不发起连接或子进程。 */
@Component
public class McpHealthChecker implements HealthCheck {

    public static final String NAME = "mcp";
    private static final Logger log = LoggerFactory.getLogger(McpHealthChecker.class);

    private final AgentMcpServerService mcpServerService;

    public McpHealthChecker(AgentMcpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean affectsCoreService() {
        return false;
    }

    @Override
    public HealthCheckResult check() {
        long start = System.nanoTime();
        try {
            long enabled = mcpServerService.countEnabled();
            if (enabled > 0) {
                return HealthCheckResult.up(NAME, false, latency(start),
                        "已配置 " + enabled + " 个启用的 MCP Server", Map.of("enabledServers", enabled));
            }
            return HealthCheckResult.degraded(NAME, false, latency(start),
                    "未配置任何启用的 MCP Server", "NOT_CONFIGURED", Map.of("enabledServers", 0L));
        } catch (Exception e) {
            log.warn("mcp health check failed: {}", e.getClass().getSimpleName());
            return HealthCheckResult.down(NAME, false, latency(start), "MCP 状态查询失败", "CHECK_FAILED", Map.of());
        }
    }

    private static long latency(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
