package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.command.CommandInfo;
import com.labex.labexagent.command.CommandRegistry;
import com.labex.labexagent.dto.PromptOptimizationRequest;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.service.AgentModelConfigService;
import com.labex.service.StudentProjectService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentCommandServicePromptOptimizationTest {

    @Test
    void optimizePromptUsesTheCurrentUsersConfiguredProvider() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        LlmProvider provider = mock(LlmProvider.class);
        StudentProject project = new StudentProject();
        project.setProjectName("demo");
        AgentModelConfig config = enabledConfig();
        LlmProvider.LlmConfig llmConfig = new LlmProvider.LlmConfig("user-key", "https://example.test/v1", "user-model", 2048, 0.2);

        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        when(modelConfigService.resolveForStudent(7, 42)).thenReturn(config);
        when(modelConfigService.hasStoredApiKey(config)).thenReturn(true);
        when(providerFactory.resolveProvider(config)).thenReturn(provider);
        when(providerFactory.buildConfig(config)).thenReturn(llmConfig);
        when(provider.chatWithTools(any(), anyList(), anyList(), same(llmConfig)))
                .thenReturn(Map.of("type", "text", "content", "structured prompt"));

        AgentCommandService service = service(projectService, modelConfigService, providerFactory);
        Map<String, String> result = service.optimizePrompt(7, 3,
                new PromptOptimizationRequest("fix the failing test", "src/App.vue", 42));

        assertEquals("structured prompt", result.get("optimizedPrompt"));
        verify(provider).chatWithTools(any(), anyList(), anyList(), same(llmConfig));
    }

    @Test
    void runCommandOnlyResolvesTheRegisteredTemplateForTheAgentRuntime() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        CommandRegistry registry = mock(CommandRegistry.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation-1");
        CommandInfo command = CommandInfo.builtin("review", "Review code", "Review this target: $ARGUMENTS");
        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        when(conversationService.ensureConversation(7, project, "conversation-1", "build", "/review src/App.vue"))
                .thenReturn(conversation);
        when(registry.getCommand("review")).thenReturn(command);
        AgentCommandService service = new AgentCommandService(projectService, conversationService, registry,
                mock(AgentModelConfigService.class), mock(LlmProviderFactory.class));

        Map<String, Object> result = service.runCommand(7, 3, Map.of(
                "command", "review",
                "message", "/review src/App.vue",
                "conversationId", "conversation-1",
                "mode", "build"));

        assertEquals("Review this target: src/App.vue", result.get("template"));
        assertEquals(true, result.get("success"));
        verify(conversationService).saveUserMessage(conversation, "/review src/App.vue");
        verify(conversationService).saveEvent(same(conversation), eq("COMMAND"), any());
    }

    @Test
    void optimizePromptRejectsMissingUserConfigurationBeforeCallingAProvider() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        StudentProject project = new StudentProject();
        project.setProjectName("demo");
        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        when(modelConfigService.resolveForStudent(7, null)).thenReturn(null);

        AgentCommandService service = service(projectService, modelConfigService, providerFactory);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.optimizePrompt(7, 3, new PromptOptimizationRequest("fix the failing test", "", null)));

        assertEquals("请先配置一个启用的模型服务后再优化提示词", error.getMessage());
        verify(providerFactory, never()).resolveProvider(any());
    }

    @Test
    void optimizePromptRejectsDisabledUserConfigurationBeforeCallingAProvider() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        StudentProject project = new StudentProject();
        project.setProjectName("demo");
        AgentModelConfig disabledConfig = enabledConfig();
        disabledConfig.setStatus(0);
        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        when(modelConfigService.resolveForStudent(7, 42)).thenReturn(disabledConfig);

        AgentCommandService service = service(projectService, modelConfigService, providerFactory);

        assertThrows(IllegalArgumentException.class,
                () -> service.optimizePrompt(7, 3, new PromptOptimizationRequest("fix the failing test", "", 42)));

        verify(providerFactory, never()).resolveProvider(any());
    }

    private AgentModelConfig enabledConfig() {
        AgentModelConfig config = new AgentModelConfig();
        config.setStatus(1);
        config.setApiKey("user-key");
        config.setProvider("openai_compatible");
        config.setModelName("user-model");
        config.setBaseUrl("https://example.test/v1");
        return config;
    }

    private AgentCommandService service(StudentProjectService projectService,
                                        AgentModelConfigService modelConfigService,
                                        LlmProviderFactory providerFactory) {
        return new AgentCommandService(
                projectService,
                mock(AgentConversationService.class),
                mock(CommandRegistry.class),
                modelConfigService,
                providerFactory
        );
    }

    @Test
    void optimizePromptUsesTheSharedReasoningBoundary() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        LlmProvider provider = mock(LlmProvider.class);
        StudentProject project = new StudentProject();
        project.setProjectName("demo");
        AgentModelConfig config = enabledConfig();
        LlmProvider.LlmConfig llmConfig = new LlmProvider.LlmConfig(
                "user-key", "https://example.test/v1", "user-model", 2048, 0.2);
        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        when(modelConfigService.resolveForStudent(7, 42)).thenReturn(config);
        when(modelConfigService.hasStoredApiKey(config)).thenReturn(true);
        when(providerFactory.resolveProvider(config)).thenReturn(provider);
        when(providerFactory.buildConfig(config)).thenReturn(llmConfig);
        when(provider.chatWithTools(any(), anyList(), anyList(), same(llmConfig))).thenReturn(Map.of(
                "type", "text",
                "content", "<THINK data-kind='hidden'>private plan</THINKING>structured prompt"));

        Map<String, String> result = service(projectService, modelConfigService, providerFactory)
                .optimizePrompt(7, 3, new PromptOptimizationRequest("fix the failing test", "src/App.vue", 42));

        assertEquals("structured prompt", result.get("optimizedPrompt"));
    }

}
