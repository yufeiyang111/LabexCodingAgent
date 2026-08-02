package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CompactionAgentTest {

    @Test
    void usesOwnedDedicatedModelWithoutToolsAndBuildsValidatedCheckpoint() {
        AgentModelConfig primary = modelConfig(1, "primary", 1);
        primary.setCompactionModelConfigId(9);
        AgentModelConfig dedicated = modelConfig(9, "summary-model", 1);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        when(modelConfigService.getOwned(7, 9)).thenReturn(dedicated);

        LlmProvider provider = mock(LlmProvider.class);
        LlmProvider.LlmConfig config = configFor(dedicated);
        when(provider.chatWithTools(anyString(), anyList(), anyList(), any(LlmProvider.LlmConfig.class))).thenReturn(Map.of(
                "type", "text",
                "content", """
                        {"summary":"Implemented the context policy and need to keep the runtime safe.",
                         "facts":["The project uses Spring Boot."],
                         "nextActions":["Run focused tests."],
                         "openRisks":["No live provider smoke test."],
                         "files":["backend/src/main/java/example.java"],
                         "verification":["Focused unit tests passed."]}
                        """));
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        when(providerFactory.resolveProvider(dedicated)).thenReturn(provider);
        when(providerFactory.buildConfig(dedicated)).thenReturn(config);

        CompactionAgent agent = new CompactionAgent(providerFactory, modelConfigService);
        CompactionAgent.Result result = agent.compact(7, primary, messages(), "Improve context management", null,
                CancellationToken.none());

        assertTrue(result.success());
        assertTrue(result.checkpoint().contains("version=\"3\""));
        assertTrue(result.checkpoint().contains("summary-model"));
        assertTrue(result.checkpoint().contains("Run focused tests."));
        assertTrue(result.dedicatedModelSelected());
        ArgumentCaptor<List<Map<String, Object>>> tools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LlmProvider.LlmConfig> usedConfig = ArgumentCaptor.forClass(LlmProvider.LlmConfig.class);
        verify(provider).chatWithTools(anyString(), anyList(), tools.capture(), usedConfig.capture());
        assertTrue(tools.getValue().isEmpty());
        assertTrue(usedConfig.getValue().temperature() == 0.0);
        assertFalse(usedConfig.getValue().promptCacheKeyEnabled());
    }

    @Test
    void rejectsMalformedOutputAndKeepsSensitiveValuesOutOfSuccessfulCheckpoint() {
        AgentModelConfig config = modelConfig(1, "primary", 1);
        LlmProvider provider = mock(LlmProvider.class);
        LlmProvider.LlmConfig llmConfig = configFor(config);
        when(provider.chatWithTools(anyString(), anyList(), anyList(), any(LlmProvider.LlmConfig.class)))
                .thenReturn(Map.of("type", "text", "content", "not json"));
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        when(providerFactory.resolveProvider(config)).thenReturn(provider);
        when(providerFactory.buildConfig(config)).thenReturn(llmConfig);
        CompactionAgent agent = new CompactionAgent(providerFactory, mock(AgentModelConfigService.class));

        CompactionAgent.Result malformed = agent.compact(7, config, messages(), "task", null, CancellationToken.none());
        assertFalse(malformed.success());

        when(provider.chatWithTools(anyString(), anyList(), anyList(), any(LlmProvider.LlmConfig.class))).thenReturn(Map.of(
                "type", "text",
                "content", """
                        {"summary":"Authorization: Bearer abcdefghijklmnopqrstuvwxyz",
                         "facts":["token=sk-abcdefghijklmnopqrstuvwxyz"],
                         "nextActions":[],"openRisks":[],"files":[],"verification":[]}
                        """));
        CompactionAgent.Result redacted = agent.compact(7, config, messages(), "task", null, CancellationToken.none());
        assertTrue(redacted.success());
        assertFalse(redacted.checkpoint().contains("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(redacted.checkpoint().contains("[REDACTED]"));
    }

    private AgentModelConfig modelConfig(int id, String modelName, int status) {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(id);
        config.setModelName(modelName);
        config.setStatus(status);
        config.setMaxTokens(4096);
        config.setContextWindowTokens(32768);
        return config;
    }

    private LlmProvider.LlmConfig configFor(AgentModelConfig modelConfig) {
        return new LlmProvider.LlmConfig("test-key", "https://api.example.test", modelConfig.getModelName(), 4096, 0.7);
    }

    private List<Map<String, Object>> messages() {
        return List.of(
                Map.of("role", "user", "content", "Please inspect the current implementation."),
                Map.of("role", "assistant", "content", "I found the runtime loop."),
                Map.of("role", "user", "content", "[Tool read_file result]\nclass AgentLoopEngine {}"));
    }

    @Test
    void stripsHiddenReasoningBeforeParsingTheStructuredCheckpoint() {
        AgentModelConfig config = modelConfig(1, "primary", 1);
        LlmProvider provider = mock(LlmProvider.class);
        LlmProvider.LlmConfig llmConfig = configFor(config);
        when(provider.chatWithTools(anyString(), anyList(), anyList(), any(LlmProvider.LlmConfig.class)))
                .thenReturn(Map.of("type", "text", "content", """
                        <THINK data-kind='hidden'>private compaction plan</THINKING>
                        {"summary":"Safe checkpoint.","facts":[],"nextActions":[],
                         "openRisks":[],"files":[],"verification":[]}
                        """));
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        when(providerFactory.resolveProvider(config)).thenReturn(provider);
        when(providerFactory.buildConfig(config)).thenReturn(llmConfig);

        CompactionAgent.Result result = new CompactionAgent(providerFactory, mock(AgentModelConfigService.class))
                .compact(7, config, messages(), "task", null, CancellationToken.none());

        assertTrue(result.success());
        assertTrue(result.checkpoint().contains("Safe checkpoint."));
        assertFalse(result.checkpoint().contains("private compaction plan"));
    }

}
