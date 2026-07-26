package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.service.AgentContextOrchestrator;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentMetricsService;
import com.labex.labexagent.service.AgentPostEditHookService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.rag.config.RagConfig;
import com.labex.rag.llm.MiniMaxChat;
import com.labex.rag.llm.OllamaChat;
import com.labex.service.AgentMcpServerService;
import com.labex.service.AgentModelConfigService;
import com.labex.service.AgentSkillService;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopEngineNextPreviewTest {

    @TempDir
    Path workspace;

    @Test
    void nextRequestPreviewDoesNotCallAProviderOrPersistConversationState() throws Exception {
        StudentProjectService projects = mock(StudentProjectService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        LlmProviderFactory providers = mock(LlmProviderFactory.class);
        AgentModelConfigService modelConfigs = mock(AgentModelConfigService.class);
        AgentSkillService skills = mock(AgentSkillService.class);
        AgentMcpServerService mcp = mock(AgentMcpServerService.class);
        AgentContextOrchestrator contextOrchestrator = mock(AgentContextOrchestrator.class);
        ContextUsageRegistry usageRegistry = mock(ContextUsageRegistry.class);

        StudentProject project = new StudentProject();
        project.setProjectId(3);
        project.setStudentId(7);
        project.setProjectName("preview-project");
        project.setWorkspacePath(workspace.toString());
        Files.createDirectories(workspace.resolve(".labex/agent-logs"));
        Files.writeString(workspace.resolve(".labex/agent-logs/previous.md"), "FOREIGN_RUN_LOG_SENTINEL");
        Files.createDirectories(workspace.resolve(".labex"));
        Files.writeString(workspace.resolve(".labex/agent-checkpoint.md"), "FOREIGN_CHECKPOINT_SENTINEL");
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        AgentModelConfig model = new AgentModelConfig();
        model.setConfigId(17);
        model.setStudentId(7);
        model.setStatus(1);
        model.setProvider("openai_compatible");
        model.setModelName("preview-model");
        model.setContextWindowTokens(32_000);

        when(projects.getOwnedProject(7, 3)).thenReturn(project);
        when(projects.readProjectFile(eq(7), eq(3), anyString())).thenReturn("");
        when(conversations.getOwnedConversation(7, 3, "conversation")).thenReturn(conversation);
        when(conversations.buildMemoryContext(7, 3, "conversation")).thenReturn("durable memory");
        when(modelConfigs.resolveForStudent(7, 17)).thenReturn(model);
        when(tools.definitionsForMode("build")).thenReturn(List.of());
        when(skills.buildPromptContext(7)).thenReturn("");
        when(mcp.buildPromptContext(7)).thenReturn("");
        when(contextOrchestrator.buildInitialBundle(eq(project), eq("src/App.vue"), anyString(), eq(""),
                eq("draft request"), anyString(), eq(false), any(AgentContext.class)))
                .thenReturn(new AgentContextOrchestrator.ContextBundle("current project context", Map.of()));

        AgentLoopEngine engine = new AgentLoopEngine(
                projects, tools, mock(AgentContextManager.class), mock(AgentCancellationRegistry.class),
                mock(MiniMaxChat.class), mock(OllamaChat.class), mock(RagConfig.class), conversations, tasks,
                providers, modelConfigs, mock(TokenTracker.class), skills, mcp, mock(PermissionService.class),
                mock(GitSnapshotService.class), mock(DiffService.class), contextOrchestrator,
                mock(AgentPostEditHookService.class), mock(AgentMetricsService.class), mock(AgentInteractionService.class),
                null, null, new ContextUsageEstimator(), usageRegistry, null);

        ContextUsageSnapshot preview = engine.previewNextRequest(7, 3, "conversation", 17,
                "src/App.vue", "build", "draft request");
        Map<String, Object> payload = preview.toPreviewPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) payload.get("previewMetadata");

        assertEquals("NEXT_REQUEST_ESTIMATE", payload.get("previewSource"));
        assertEquals(Boolean.TRUE, metadata.get("nextUserMessageIncluded"));
        assertTrue(((List<?>) payload.get("previewSections")).size() > 0);
        assertFalse(((List<?>) payload.get("previewSections")).isEmpty());
        assertFalse(payload.toString().contains("FOREIGN_RUN_LOG_SENTINEL"));
        assertFalse(payload.toString().contains("FOREIGN_CHECKPOINT_SENTINEL"));
        verify(conversations, never()).saveUserMessage(any(AgentConversation.class), anyString());
        verify(conversations, never()).saveEvent(any(AgentConversation.class), anyString(), any());
        verifyNoInteractions(providers, tasks, usageRegistry);
    }
}
