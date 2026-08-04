package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class CreatePlanToolVerificationTest {

    @TempDir
    Path workspace;

    @Test
    void persistsThePlanAndRejectsCompletingVerificationUntilTrustedEvidenceExists() throws Exception {
        AgentRunPlanService plans = Mockito.mock(AgentRunPlanService.class);
        AgentRunPlanService.Projection active = projection(List.of(
                new AgentRunPlanService.PlanItem("Verify frontend build", "Run npm build verification", "in_progress")),
                1L, 0);
        AgentRunPlanService.Projection completed = projection(List.of(
                new AgentRunPlanService.PlanItem("Verify frontend build", "Run npm build verification", "completed")),
                2L, -1);
        when(plans.replace(eq(71L), eq(4L), Mockito.anyList(), eq("create_plan"))).thenReturn(active);
        when(plans.load(71L)).thenReturn(active);
        when(plans.complete(71L, 4L, 0, "create_plan")).thenReturn(completed);
        CreatePlanTool tool = new CreatePlanTool(plans);
        AgentContext context = new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), 0);
        context.setExecutionEpoch(4L);
        JsonObject create = new JsonObject();
        create.addProperty("action", "create");
        JsonArray tasks = new JsonArray();
        JsonObject task = new JsonObject();
        task.addProperty("title", "Verify frontend build");
        task.addProperty("description", "Run npm build verification");
        tasks.add(task);
        create.add("tasks", tasks);

        assertThat(tool.execute(context, create).isSuccess()).isTrue();
        verify(plans).replace(eq(71L), eq(4L), Mockito.anyList(), eq("create_plan"));
        assertThat(context.getPlanRevision()).isEqualTo(1L);

        JsonObject complete = new JsonObject();
        complete.addProperty("action", "complete");
        complete.addProperty("task_index", 1);
        assertThat(tool.execute(context, complete).isSuccess()).isFalse();
        context.incrementVerificationCount();
        assertThat(tool.execute(context, complete).isSuccess()).isFalse();
        verify(plans, never()).complete(71L, 4L, 0, "create_plan");

        context.recordTrustedVerification("run_tests");
        assertThat(tool.execute(context, complete).isSuccess()).isTrue();
        verify(plans).complete(71L, 4L, 0, "create_plan");
        assertThat(context.getPlan().get(0).isCompleted()).isTrue();
        assertThat(context.getCurrentPlanIndex()).isEqualTo(-1);
        assertThat(context.getPlanRevision()).isEqualTo(2L);
    }

    private AgentRunPlanService.Projection projection(List<AgentRunPlanService.PlanItem> items,
                                                       long revision, int currentIndex) {
        return new AgentRunPlanService.Projection(71L, 4L, revision, items, currentIndex, 20L + revision);
    }
}
