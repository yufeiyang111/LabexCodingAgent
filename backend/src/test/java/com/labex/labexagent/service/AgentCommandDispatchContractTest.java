package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.StudentProject;
import com.labex.labexagent.command.CommandInfo;
import com.labex.labexagent.command.CommandInfo.ClientAction;
import com.labex.labexagent.command.CommandInfo.CommandDispatch;
import com.labex.labexagent.command.CommandRegistry;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.service.AgentModelConfigService;
import com.labex.service.StudentProjectService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentCommandDispatchContractTest {

    @Test
    void classifiesUiCommandsSeparatelyFromAgentPrompts() {
        CommandRegistry registry = new CommandRegistry();

        CommandInfo newCommand = registry.getCommand("new");
        CommandInfo clearAlias = registry.getCommand("clear");
        CommandInfo compact = registry.getCommand("summarize");
        CommandInfo review = registry.getCommand("review");
        CommandInfo rename = registry.getCommand("rename");

        assertEquals(CommandDispatch.CLIENT_ACTION, newCommand.dispatch());
        assertEquals(ClientAction.SESSION_NEW, newCommand.clientAction());
        assertEquals(newCommand, clearAlias);
        assertEquals(List.of("clear"), newCommand.aliases());
        assertEquals(CommandDispatch.CLIENT_ACTION, compact.dispatch());
        assertEquals(ClientAction.CONVERSATION_COMPACT, compact.clientAction());
        assertEquals(CommandDispatch.AGENT_PROMPT, review.dispatch());
        assertTrue(review.resolveTemplate("src/App.vue").contains("src/App.vue"));
        assertEquals(CommandDispatch.UNAVAILABLE, rename.dispatch());
    }

    @Test
    void rejectsUnclassifiedOrIncompleteCommandMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new CommandInfo(
                "implicit", "未分类命令", "prompt", null, null, false,
                CommandInfo.CommandSource.BUILTIN, List.of(), List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CommandInfo(
                "client", "缺少客户端动作", null, null, null, false,
                CommandInfo.CommandSource.BUILTIN, List.of(), List.of(),
                CommandDispatch.CLIENT_ACTION, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CommandInfo(
                "agent", "缺少模板", "", null, null, false,
                CommandInfo.CommandSource.BUILTIN, List.of(), List.of(),
                CommandDispatch.AGENT_PROMPT, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CommandInfo(
                "unavailable", "缺少不可用原因", null, null, null, false,
                CommandInfo.CommandSource.BUILTIN, List.of(), List.of(),
                CommandDispatch.UNAVAILABLE, null, ""));
    }
    @Test
    void clientActionResolutionDoesNotCreateOrPolluteAConversation() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        CommandRegistry registry = mock(CommandRegistry.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        when(registry.getCommand("new")).thenReturn(CommandInfo.clientAction(
                "new", "新建会话", ClientAction.SESSION_NEW, "clear"));
        AgentCommandService service = new AgentCommandService(
                projectService,
                registry,
                mock(AgentModelConfigService.class),
                mock(LlmProviderFactory.class));

        Map<String, Object> result = service.runCommand(7, 3, Map.of(
                "command", "new",
                "message", "/new",
                "conversationId", "conversation-1",
                "mode", "build"));

        assertEquals("CLIENT_ACTION", result.get("dispatch"));
        assertEquals("SESSION_NEW", result.get("action"));
        assertEquals("new", result.get("command"));
        assertFalse(result.containsKey("conversationId"));
    }

    @Test
    void validatesTheProviderPromptWhilePreservingTheVisibleSlashCommand() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        when(projectService.getOwnedProject(7, 3)).thenReturn(project);
        AgentCommandService service = new AgentCommandService(
                projectService,
                new CommandRegistry(),
                mock(AgentModelConfigService.class),
                mock(LlmProviderFactory.class));
        String visibleMessage = "/review src/App.vue";
        String effectivePrompt = new CommandRegistry().getCommand("review")
                .resolveTemplate("src/App.vue").trim();
        AgentStreamRequest request = new AgentStreamRequest();
        request.setDisplayMessage(visibleMessage);
        request.setMessage(effectivePrompt);

        service.prepareAgentStreamRequest(7, 3, request);

        assertEquals(visibleMessage, request.userVisibleMessage());
        assertEquals(effectivePrompt, request.getMessage());

        request.setMessage("different prompt");
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareAgentStreamRequest(7, 3, request));
    }

    @Test
    @SuppressWarnings("unchecked")
    void availableCatalogExcludesUnavailableCommandsAndCarriesAliases() {
        AgentCommandService service = new AgentCommandService(
                mock(StudentProjectService.class),
                new CommandRegistry(),
                mock(AgentModelConfigService.class),
                mock(LlmProviderFactory.class));

        List<Map<String, Object>> commands = (List<Map<String, Object>>) service.getAvailableCommands().get("commands");
        Map<String, Object> newCommand = commands.stream()
                .filter(command -> "new".equals(command.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals("CLIENT_ACTION", newCommand.get("dispatch"));
        assertEquals("SESSION_NEW", newCommand.get("action"));
        assertEquals(List.of("clear"), newCommand.get("aliases"));
        assertFalse(commands.stream().anyMatch(command -> "rename".equals(command.get("name"))));
        assertTrue(commands.stream().anyMatch(command -> "review".equals(command.get("name"))
                && "AGENT_PROMPT".equals(command.get("dispatch"))));
    }
}
