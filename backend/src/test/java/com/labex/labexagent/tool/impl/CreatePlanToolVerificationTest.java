package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.run.AgentRunProgressProjectionService;
import com.labex.labexagent.run.PlanVerificationEvidenceService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class CreatePlanToolVerificationTest {

    @TempDir
    Path workspace;

    @Test
    void persistsThePlanAndRejectsCompletingVerificationUntilTrustedEvidenceExists() throws Exception {
        AgentRunPlanService plans = Mockito.mock(AgentRunPlanService.class);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);
        AgentRunPlanService.Projection active = projection(List.of(
                new AgentRunPlanService.PlanItem("Verify frontend build", "Run npm build verification", "in_progress")),
                1L, 0);
        AgentRunPlanService.Projection completed = projection(List.of(
                new AgentRunPlanService.PlanItem("Verify frontend build", "Run npm build verification", "completed")),
                2L, -1);
        when(plans.replace(eq(fence), eq(71L), eq(4L), Mockito.anyList(), eq("create_plan"))).thenReturn(active);
        when(plans.load(71L)).thenReturn(active);
        when(plans.complete(eq(fence), eq(71L), eq(4L), eq(0), eq("create_plan"))).thenReturn(completed);
        AgentRunProgressProjectionService progress = Mockito.mock(AgentRunProgressProjectionService.class);
        when(progress.load(71L, 4L)).thenReturn(progressProjection(java.util.Set.of()));
        PlanVerificationEvidenceService evidence = Mockito.mock(PlanVerificationEvidenceService.class);
        when(evidence.assess(eq(71L), eq(4L), any())).thenReturn(
                new PlanVerificationEvidenceService.Assessment(true, false, "verification_missing", "build", "", ""));
        CreatePlanTool tool = new CreatePlanTool(plans, progress, evidence);
        AgentContext context = new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), 0);
        context.setExecutionEpoch(4L);
        context.setExecutionFence(fence);
        JsonObject create = new JsonObject();
        create.addProperty("action", "create");
        JsonArray tasks = new JsonArray();
        JsonObject task = new JsonObject();
        task.addProperty("title", "Verify frontend build");
        task.addProperty("description", "Run npm build verification");
        tasks.add(task);
        create.add("tasks", tasks);

        assertThat(tool.execute(context, create).isSuccess()).isTrue();
        ArgumentCaptor<ExecutionFence> fenceCaptor = ArgumentCaptor.forClass(ExecutionFence.class);
        verify(plans).replace(fenceCaptor.capture(), eq(71L), eq(4L), Mockito.anyList(), eq("create_plan"));
        assertThat(fenceCaptor.getValue()).isEqualTo(fence);
        assertThat(context.getPlanRevision()).isEqualTo(1L);

        JsonObject complete = new JsonObject();
        complete.addProperty("action", "complete");
        complete.addProperty("task_index", 1);
        assertThat(tool.execute(context, complete).isSuccess()).isFalse();
        assertThat(tool.execute(context, complete).isSuccess()).isFalse();
        verify(plans, never()).complete(eq(fence), eq(71L), eq(4L), eq(0), eq("create_plan"));

        when(progress.load(71L, 4L)).thenReturn(progressProjection(java.util.Set.of("run_tests")));
        when(evidence.assess(eq(71L), eq(4L), any())).thenReturn(
                new PlanVerificationEvidenceService.Assessment(true, true, "", "build", "", "run_tests"));
        assertThat(tool.execute(context, complete).isSuccess()).isTrue();
        verify(plans).complete(eq(fence), eq(71L), eq(4L), eq(0), eq("create_plan"));
        assertThat(context.getPlan().get(0).isCompleted()).isTrue();
        assertThat(context.getCurrentPlanIndex()).isEqualTo(-1);
        assertThat(context.getPlanRevision()).isEqualTo(2L);
    }


    private AgentRunProgressProjectionService.Projection progressProjection(java.util.Set<String> trustedSources) {
        return new AgentRunProgressProjectionService.Projection(
                71L, 4L, "verify", 0, trustedSources.isEmpty() ? 0 : 1, false,
                trustedSources, java.util.Set.of(), "call-1", "run_tests", "success", "",
                "", "running", "", "", "", 1L, 1L, "agent_run_part_event");
    }

    @Test
    void rejectsPlanWriteWithoutAnActiveFenceBeforeCallingThePlanService() throws Exception {
        AgentRunPlanService plans = Mockito.mock(AgentRunPlanService.class);
        CreatePlanTool tool = new CreatePlanTool(plans, Mockito.mock(AgentRunProgressProjectionService.class));
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

        assertThatThrownBy(() -> tool.execute(context, create))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("invalid-fence");
        verify(plans, never()).replace(any(), anyLong(), anyLong(), anyList(), anyString());
        verify(plans, never()).complete(any(), anyLong(), anyLong(), anyInt(), anyString());
        verify(plans, never()).update(any(), anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString());
    }

    private AgentRunPlanService.Projection projection(List<AgentRunPlanService.PlanItem> items,
                                                       long revision, int currentIndex) {
        return new AgentRunPlanService.Projection(71L, 4L, revision, items, currentIndex, 20L + revision);
    }
}
