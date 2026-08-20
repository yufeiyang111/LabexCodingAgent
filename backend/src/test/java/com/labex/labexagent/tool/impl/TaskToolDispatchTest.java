package com.labex.labexagent.tool.impl;
import static org.junit.jupiter.api.Assertions.assertTrue;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;import com.google.gson.JsonObject;import com.labex.entity.AgentSubagent;import com.labex.entity.StudentProject;import com.labex.labexagent.run.SubagentDispatchService;import com.labex.labexagent.runtime.AgentContext;import com.labex.labexagent.service.ProjectIndexService;import java.nio.file.Path;import java.util.concurrent.CompletableFuture;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class TaskToolDispatchTest {
    @TempDir Path root;

    @Test
    void returnsBackgroundTaskIdWithoutBlocking() {
        ProjectIndexService index = mock(ProjectIndexService.class);
        SubagentDispatchService dispatch = mock(SubagentDispatchService.class);
        StudentProject p = new StudentProject();
        p.setWorkspacePath(root.toString());
        AgentContext c = AgentContext.create("s", 7, p, "conv", 9L);
        AgentSubagent a = new AgentSubagent();
        a.setSubagentId(3L);
        when(index.buildProjectDigest(any(), any())).thenReturn("digest");
        when(dispatch.dispatch(eq("s"), eq(7), eq(p), eq("conv"), eq(9L), anyString(), anyString(), isNull(), eq(4096), eq("[]"), eq("[]"), eq(true)))
                .thenReturn(new SubagentDispatchService.Dispatch(a, new CompletableFuture<>()));
        JsonObject args = new JsonObject();
        args.addProperty("name", "Frontend Arch Explorer");
        args.addProperty("description", "research");
        args.addProperty("background", true);
        var result = new TaskTool(index, dispatch).execute(c, args);
        if (!result.isSuccess()) throw new RuntimeException(result.getContent());
        assertTrue(result.getContent().contains("state=\"running\""));
        assertTrue(result.getContent().contains("name=\"Frontend Arch Explorer\""));
    }

    @Test
    void formatsSubagentOutputWithCustomName() {
        ProjectIndexService index = mock(ProjectIndexService.class);
        SubagentDispatchService dispatch = mock(SubagentDispatchService.class);
        StudentProject p = new StudentProject();
        p.setWorkspacePath(root.toString());
        AgentContext c = AgentContext.create("s", 7, p, "conv", 9L);
        AgentSubagent a = new AgentSubagent();
        a.setSubagentId(5L);
        a.setSummary("## Findings\nEverything looks good.\n## Relevant Files\n- src/main.js\n## Suggested Next Steps\nRefactor routes.");
        when(index.buildProjectDigest(any(), any())).thenReturn("digest");
        when(dispatch.dispatch(eq("s"), eq(7), eq(p), eq("conv"), eq(9L), eq("API Spec Scout (scout)"), anyString(), isNull(), eq(4096), eq("[]"), eq("[]"), eq(false)))
                .thenReturn(new SubagentDispatchService.Dispatch(a, CompletableFuture.completedFuture(null)));
        JsonObject args = new JsonObject();
        args.addProperty("name", "API Spec Scout");
        args.addProperty("subagent_type", "scout");
        args.addProperty("description", "explore API spec");
        args.addProperty("prompt", "check endpoints");
        var result = new TaskTool(index, dispatch).execute(c, args);
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("name=\"API Spec Scout\""));
        assertTrue(result.getContent().contains("<name>API Spec Scout</name>"));
    }
}

