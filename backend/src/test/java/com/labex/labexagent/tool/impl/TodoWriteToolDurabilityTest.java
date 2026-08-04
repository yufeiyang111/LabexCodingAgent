package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TodoWriteToolDurabilityTest {

    @TempDir
    Path workspace;

    @Test
    void parsesMarkdownCheckboxesAndPersistsOneCompletePlanSnapshot() {
        AgentRunPlanService plans = Mockito.mock(AgentRunPlanService.class);
        AgentRunPlanService.Projection projection = projection(List.of(
                new AgentRunPlanService.PlanItem("Inspect state", "", "completed"),
                new AgentRunPlanService.PlanItem("Run restart test", "", "in_progress")));
        when(plans.replace(eq(71L), eq(4L), Mockito.anyList(), eq("todo_write"))).thenReturn(projection);
        TodoWriteTool tool = new TodoWriteTool(plans);
        AgentContext context = context();
        JsonObject args = new JsonObject();
        args.addProperty("todos", "- [x] Inspect state\n- [ ] Run restart test");

        ToolResult result = tool.execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<List<AgentRunPlanService.PlanDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(plans).replace(eq(71L), eq(4L), drafts.capture(), eq("todo_write"));
        assertThat(drafts.getValue()).extracting(AgentRunPlanService.PlanDraft::title)
                .containsExactly("Inspect state", "Run restart test");
        assertThat(drafts.getValue()).extracting(AgentRunPlanService.PlanDraft::completed)
                .containsExactly(true, false);
        assertThat(context.getPlan()).hasSize(2);
        assertThat(context.getPlan().get(0).isCompleted()).isTrue();
        assertThat(context.getCurrentPlanIndex()).isEqualTo(1);
        assertThat(context.getPlanRevision()).isEqualTo(2L);
    }

    @Test
    void rejectsMoreThanThirtyTodoItemsWithoutWriting() {
        AgentRunPlanService plans = Mockito.mock(AgentRunPlanService.class);
        TodoWriteTool tool = new TodoWriteTool(plans);
        JsonObject args = new JsonObject();
        args.addProperty("todos", String.join("\n", java.util.stream.IntStream.rangeClosed(1, 31)
                .mapToObj(index -> "- item " + index).toList()));

        ToolResult result = tool.execute(context(), args);

        assertThat(result.isSuccess()).isFalse();
        Mockito.verifyNoInteractions(plans);
    }

    private AgentContext context() {
        AgentContext context = new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), 0);
        context.setExecutionEpoch(4L);
        return context;
    }

    private AgentRunPlanService.Projection projection(List<AgentRunPlanService.PlanItem> items) {
        return new AgentRunPlanService.Projection(71L, 4L, 2L, items, 1, 22L);
    }
}
