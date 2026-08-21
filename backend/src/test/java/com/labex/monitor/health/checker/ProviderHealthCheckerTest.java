package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.monitor.health.checker.ProviderHealthChecker;
import com.labex.rag.config.RagConfig;
import com.labex.service.AgentModelConfigService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderHealthCheckerTest {

    private final LlmProviderFactory factory = mock(LlmProviderFactory.class);
    private final AgentModelConfigService configService = mock(AgentModelConfigService.class);
    private final RagConfig ragConfig = mock(RagConfig.class);
    private final ProviderHealthChecker checker =
            new ProviderHealthChecker(factory, configService, ragConfig);

    @Test
    void providerRegisteredAndModelConfiguredIsUp() {
        when(factory.getProviderInfo()).thenReturn(Map.of("openai_compatible", Map.of("id", "openai_compatible")));
        when(configService.countEnabled()).thenReturn(1L);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.name()).isEqualTo("provider");
        assertThat(result.affectsCoreService()).isTrue();
    }

    @Test
    void providerRegisteredButNoModelIsDegraded() {
        when(factory.getProviderInfo()).thenReturn(Map.of("openai_compatible", Map.of("id", "openai_compatible")));
        when(configService.countEnabled()).thenReturn(0L);
        when(ragConfig.getMiniMaxApiKey()).thenReturn("");

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(result.errorCode()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void providerRegisteredButNoModelWithDefaultKeyIsUp() {
        when(factory.getProviderInfo()).thenReturn(Map.of("openai_compatible", Map.of("id", "openai_compatible")));
        when(configService.countEnabled()).thenReturn(0L);
        when(ragConfig.getMiniMaxApiKey()).thenReturn("sk-default-key");

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void noProviderRegisteredIsDown() {
        when(factory.getProviderInfo()).thenReturn(Map.of());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.errorCode()).isEqualTo("NO_PROVIDER");
    }

    @Test
    void checkFailureIsDownWithoutLeakingKey() {
        when(factory.getProviderInfo()).thenThrow(new IllegalStateException("sk-secret-api-key-abc123"));

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.safeMessage()).doesNotContain("sk-secret-api-key-abc123");
    }
}
