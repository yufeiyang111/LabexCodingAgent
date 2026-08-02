package com.labex.labexagent.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredImageUnderstandingServiceTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsImageUnderstandingWhenTheSelectedModelHasNoMultimodalCapability() {
        AgentModelConfig config = modelConfig(false);
        AgentModelConfigService configService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        when(configService.resolveForStudent(42, 7)).thenReturn(config);

        ConfiguredImageUnderstandingService service = new ConfiguredImageUnderstandingService(
                configService, providerFactory, source -> "data:image/png;base64,AA==");

        ConfiguredImageUnderstandingService.ImageAnalysisResult result = service.analyzeImage(
                context(), "Describe this screenshot", "screenshots/error.png", "error.png");

        assertFalse(result.success());
        assertTrue(result.content().contains("does not enable image understanding"));
        verifyNoInteractions(providerFactory);
    }

    @Test
    void sendsResolvedImageAsOpenAiCompatibleMultimodalContentUsingTheSelectedModel() {
        AgentModelConfig config = modelConfig(true);
        AgentModelConfigService configService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        AtomicReference<List<Map<String, Object>>> capturedMessages = new AtomicReference<>();
        LlmProvider provider = new LlmProvider() {
            @Override public String getProviderId() { return "openai_compatible"; }
            @Override public String getProviderName() { return "OpenAI Compatible"; }
            @Override public boolean supportsStreaming() { return true; }
            @Override public boolean supportsToolCalling() { return true; }
            @Override public Map<String, Object> chatWithTools(String system, List<Map<String, Object>> messages,
                                                                 List<Map<String, Object>> tools, LlmConfig llmConfig) {
                capturedMessages.set(messages);
                return Map.of("type", "text", "content", "visible screenshot text");
            }
            @Override public void chatStream(String system, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools, LlmConfig llmConfig,
                                             java.util.function.Consumer<StreamChunk> onChunk) { }
        };
        when(configService.resolveForStudent(42, 7)).thenReturn(config);
        when(configService.hasStoredApiKey(config)).thenReturn(true);
        when(providerFactory.resolveProvider(config)).thenReturn(provider);
        when(providerFactory.buildConfig(config)).thenReturn(new LlmProvider.LlmConfig(
                "key", "https://api.example.test/v1", "vision-model", 256, 0.1));

        ConfiguredImageUnderstandingService service = new ConfiguredImageUnderstandingService(
                configService, providerFactory, source -> "data:image/png;base64,AA==");

        ConfiguredImageUnderstandingService.ImageAnalysisResult result = service.analyzeImage(
                context(), "Read the error", "screenshots/error.png", "error.png");

        assertTrue(result.success());
        assertEquals("visible screenshot text", result.content());
        Object content = capturedMessages.get().get(0).get("content");
        assertTrue(content instanceof List<?>);
        assertTrue(content.toString().contains("image_url"));
        assertTrue(content.toString().contains("data:image/png;base64,AA=="));
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(5);
        project.setWorkspacePath(workspace.toString());
        AgentContext context = AgentContext.create("session-1", 42, project, "conversation-1", 9L);
        context.setModelConfigId(7);
        return context;
    }

    private static AgentModelConfig modelConfig(boolean imageInputEnabled) {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(7);
        config.setStudentId(42);
        config.setConfigName("Vision model");
        config.setImageInputEnabled(imageInputEnabled ? 1 : 0);
        return config;
    }

    @Test
    void neverPromotesProviderReasoningIntoTheVisibleImageAnswer() {
        AgentModelConfig config = modelConfig(true);
        AgentModelConfigService configService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.resolveForStudent(42, 7)).thenReturn(config);
        when(configService.hasStoredApiKey(config)).thenReturn(true);
        when(providerFactory.resolveProvider(config)).thenReturn(provider);
        when(providerFactory.buildConfig(config)).thenReturn(new LlmProvider.LlmConfig(
                "key", "https://api.example.test/v1", "vision-model", 256, 0.1));
        when(provider.chatWithTools(any(), anyList(), anyList(), any())).thenReturn(Map.of(
                "type", "text",
                "content", "<THINK data-kind='hidden'>private plan</THINKING>visible screenshot text",
                "thinking", "dedicated private plan"));
        ConfiguredImageUnderstandingService service = new ConfiguredImageUnderstandingService(
                configService, providerFactory, source -> "data:image/png;base64,AA==");

        ConfiguredImageUnderstandingService.ImageAnalysisResult result = service.analyzeImage(
                context(), "Read the error", "screenshots/error.png", "error.png");

        assertTrue(result.success());
        assertEquals("visible screenshot text", result.content());
    }

}
