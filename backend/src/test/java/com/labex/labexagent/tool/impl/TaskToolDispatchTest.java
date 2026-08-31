package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.AgentSubagent;
import com.labex.entity.AgentConversation;
import com.labex.labexagent.run.AgentSubagentProperties;
import com.labex.labexagent.run.AgentSubagentService;
import com.labex.labexagent.run.SubagentCompletionRegistry;
import com.labex.labexagent.run.SubagentLaunchService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentContext;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskToolDispatchTest {

    @Test
    void returnsBackgroundTaskIdWithoutBlocking() {
        SubagentLaunchService launch = mock(SubagentLaunchService.class);
        AgentSubagent row = row(3L, "queued");
        when(launch.launch(any())).thenReturn(launch(row, conversation(), never()));
        var result = tool(launch).execute(context(), args("Frontend Arch Explorer", "research", true));
        if (!result.isSuccess()) throw new RuntimeException(result.getContent());
        assertTrue(result.getContent().contains("state=\"completed\"") || result.getContent().contains("background"));
        ArgumentCaptor<SubagentLaunchService.LaunchSpec> spec = ArgumentCaptor.forClass(SubagentLaunchService.LaunchSpec.class);
        verify(launch).launch(spec.capture());
        assertTrue(spec.getValue().background());
    }

    @Test
    void blocksUntilTerminalAndProjectsCompletedSummary() {
        SubagentLaunchService launch = mock(SubagentLaunchService.class);
        AgentSubagent dispatched = row(5L, "running");
        AgentSubagent finished = row(5L, "completed");
        finished.setSummary("## Findings\nEverything looks good.");
        when(launch.launch(any())).thenReturn(launch(dispatched, conversation(),
                java.util.concurrent.CompletableFuture.completedFuture(null)));
        when(subagents().findById(5L)).thenReturn(finished);
        var result = tool(launch).execute(context(), args("API Spec Scout", "explore API spec", false));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("id=\"5\""));
        assertTrue(result.getContent().contains("Everything looks good."));
    }

    @Test
    void returnsFailureWhenThePersistedSubagentEndsInFailedState() {
        SubagentLaunchService launch = mock(SubagentLaunchService.class);
        AgentSubagent dispatched = row(19L, "running");
        AgentSubagent finished = row(19L, "failed");
        finished.setSummary("subagent token budget exceeded");
        when(launch.launch(any())).thenReturn(launch(dispatched, conversation(),
                CompletableFuture.completedFuture(null)));
        when(subagents().findById(19L)).thenReturn(finished);
        var result = tool(launch).execute(context(), args("Deep Diver", "inspect provider", false));
        assertTrue(!result.isSuccess());
        assertTrue(result.getContent().contains("state=\"error\""));
        assertTrue(result.getContent().contains("subagent token budget exceeded"));
    }

    @Test
    void injectsPriorSessionContextWhenTaskIdIsProvided() {
        SubagentLaunchService launch = mock(SubagentLaunchService.class);
        AgentSubagent prior = row(11L, "completed");
        prior.setInstructions("old prompt");
        prior.setSummary("old findings");
        when(subagents().findById(11L)).thenReturn(prior);
        AgentSubagent finished12 = row(12L, "completed");
        finished12.setSummary("ok");
        when(subagents().findById(12L)).thenReturn(finished12);
        when(launch.launch(any())).thenReturn(launch(finished12, conversation(),
                CompletableFuture.completedFuture(null)));
        JsonObject args = args("Follow-up", "what about auth?", false);
        args.addProperty("task_id", "11");
        var result = tool(launch).execute(context(), args);
        assertTrue(result.isSuccess());
        ArgumentCaptor<SubagentLaunchService.LaunchSpec> spec = ArgumentCaptor.forClass(SubagentLaunchService.LaunchSpec.class);
        verify(launch).launch(spec.capture());
        assertTrue(spec.getValue().prompt().contains("Previous Subagent Session #11"));
        assertTrue(spec.getValue().prompt().contains("old findings"));
    }

    private final AgentSubagentService subagentsMock = mock(AgentSubagentService.class);

    private AgentSubagentService subagents() {
        return subagentsMock;
    }

    private TaskTool tool(SubagentLaunchService launch) {
        return new TaskTool(launch, subagents(), mock(AgentCancellationRegistry.class),
                new AgentSubagentProperties());
    }

    private static AgentSubagent row(long id, String status) {
        AgentSubagent row = new AgentSubagent();
        row.setSubagentId(id);
        row.setStatus(status);
        return row;
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("child-conv");
        return conversation;
    }

    private static SubagentLaunchService.Launch launch(AgentSubagent row, AgentConversation conversation,
                                                       CompletableFuture<Void> completion) {
        return new SubagentLaunchService.Launch(row, conversation, completion);
    }

    private static CompletableFuture<Void> never() {
        return new CompletableFuture<>();
    }

    private static AgentContext context() {
        StudentProjectForTest p = new StudentProjectForTest();
        return AgentContext.create("parent-session", 7, p.project(), "conv-1", 9L);
    }

    private static JsonObject args(String name, String description, boolean background) {
        JsonObject args = new JsonObject();
        args.addProperty("name", name);
        args.addProperty("description", description);
        args.addProperty("prompt", "check endpoints");
        args.addProperty("background", background);
        return args;
    }

    /** 最小项目桩：仅提供 workspacePath。 */
    private static final class StudentProjectForTest {
        private StudentProjectForTest() {
        }

        com.labex.entity.StudentProject project() {
            com.labex.entity.StudentProject project = new com.labex.entity.StudentProject();
            project.setWorkspacePath(System.getProperty("java.io.tmpdir"));
            return project;
        }
    }
}
