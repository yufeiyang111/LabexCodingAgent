package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentSubagent;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmSubagentExecutorTest {

    @Test
    void exposesOnlyVisibleTextAndDoesNotAppendTheDoneSnapshotTwice() {
        LlmProviderFactory providers = mock(LlmProviderFactory.class);
        AgentModelConfigService configs = mock(AgentModelConfigService.class);
        AgentSubagentEventService events = mock(AgentSubagentEventService.class);
        AgentModelConfig config = new AgentModelConfig();
        LlmProvider.LlmConfig runtimeConfig = new LlmProvider.LlmConfig(
                "test-key", "https://example.com", "test-model", 1024, 0.0);
        LlmProvider provider = scriptedProvider(callback -> {
            callback.accept(chunk("thinking_delta", "<THINK>private chain</THINK>"));
            callback.accept(chunk("text_delta", "Visible <TH"));
            callback.accept(chunk("text_delta", "INK data-kind='hidden'>embedded private</THINK"));
            callback.accept(chunk("text_delta", "ING> answer"));
            callback.accept(chunk("done", "Visible <THINK>embedded private</THINK> answer"));
        });
        when(configs.resolveForStudent(eq(7), eq(11))).thenReturn(config);
        when(providers.resolveProvider(config)).thenReturn(provider);
        when(providers.buildConfig(config)).thenReturn(runtimeConfig);

        AgentContext context = mock(AgentContext.class);
        when(context.getStudentId()).thenReturn(7);
        when(context.getCancellationToken()).thenReturn(CancellationToken.none());
        AgentSubagent subagent = new AgentSubagent();
        subagent.setSubagentId(31L);
        subagent.setModelConfigId(11);
        subagent.setInstructions("review the implementation");

        String output = new LlmSubagentExecutor(providers, configs, events).execute(context, subagent);

        assertEquals("Visible  answer", output);
        assertFalse(output.contains("private"));
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(events, org.mockito.Mockito.times(2)).append(eq(31L), eq("DELTA"), payloads.capture());
        assertEquals(List.of("Visible ", " answer"), payloads.getAllValues());
    }

    @Test
    void rejectsProviderErrorsInsteadOfPublishingThemAsVisibleOutput() {
        LlmProviderFactory providers = mock(LlmProviderFactory.class);
        AgentModelConfigService configs = mock(AgentModelConfigService.class);
        AgentSubagentEventService events = mock(AgentSubagentEventService.class);
        AgentModelConfig config = new AgentModelConfig();
        LlmProvider provider = scriptedProvider(callback ->
                callback.accept(chunk("error", "provider <think>private</think> failed")));
        when(configs.resolveForStudent(eq(7), eq(11))).thenReturn(config);
        when(providers.resolveProvider(config)).thenReturn(provider);
        when(providers.buildConfig(config)).thenReturn(new LlmProvider.LlmConfig(
                "test-key", "https://example.com", "test-model", 1024, 0.0));

        AgentContext context = mock(AgentContext.class);
        when(context.getStudentId()).thenReturn(7);
        when(context.getCancellationToken()).thenReturn(CancellationToken.none());
        AgentSubagent subagent = new AgentSubagent();
        subagent.setSubagentId(32L);
        subagent.setModelConfigId(11);
        subagent.setInstructions("review the implementation");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new LlmSubagentExecutor(providers, configs, events).execute(context, subagent));

        assertEquals("provider  failed", failure.getMessage());
        verify(events).append(32L, "ERROR", "provider  failed");
        verify(events, org.mockito.Mockito.never()).append(eq(32L), eq("DELTA"), org.mockito.ArgumentMatchers.anyString());
    }
    private static LlmProvider scriptedProvider(Consumer<Consumer<LlmProvider.StreamChunk>> script) {
        return new LlmProvider() {
            @Override public String getProviderId() { return "test"; }
            @Override public String getProviderName() { return "test"; }
            @Override public boolean supportsStreaming() { return true; }
            @Override public boolean supportsToolCalling() { return false; }
            @Override public Map<String, Object> chatWithTools(String systemPrompt,
                                                               List<Map<String, Object>> messages,
                                                               List<Map<String, Object>> tools,
                                                               LlmConfig config) {
                return Map.of();
            }
            @Override public void chatStream(String systemPrompt,
                                             List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools,
                                             LlmConfig config,
                                             Consumer<StreamChunk> callback) {
                script.accept(callback);
            }
        };
    }

    private static LlmProvider.StreamChunk chunk(String type, String content) {
        return new LlmProvider.StreamChunk(type, content, null, null, null, "done".equals(type), Map.of());
    }
}
