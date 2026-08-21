package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.monitor.health.checker.McpHealthChecker;
import com.labex.service.AgentMcpServerService;
import org.junit.jupiter.api.Test;

class McpHealthCheckerTest {

    private final AgentMcpServerService mcpService = mock(AgentMcpServerService.class);
    private final McpHealthChecker checker = new McpHealthChecker(mcpService);

    @Test
    void enabledMcpServersExistIsUp() {
        when(mcpService.countEnabled()).thenReturn(2L);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.name()).isEqualTo("mcp");
        assertThat(result.affectsCoreService()).isFalse();
    }

    @Test
    void noMcpServersConfiguredIsDegraded() {
        when(mcpService.countEnabled()).thenReturn(0L);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(result.errorCode()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void queryFailureIsDownWithoutLeakingDetails() {
        when(mcpService.countEnabled()).thenThrow(new IllegalStateException("nested cause: authHeader=Bearer xyz"));

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.safeMessage()).doesNotContain("Bearer");
        assertThat(result.safeMessage()).doesNotContain("authHeader");
    }
}
