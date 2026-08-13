package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunModeService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentTaskMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * plan_exit 必须经 {@link AgentRunModeService} 完成 durable、fenced 的模式切换；
 * 没有有效 fence 或没有计划摘要时，绝不能切换模式或产生任何持久化副作用。
 */
class PlanExitToolTest {

    @Test
    void switchesToBuildModeThroughTheDurableModeService() throws Exception {
        AgentTask task = task();
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(tasks.update(any(), any())).thenReturn(1);
        when(tasks.selectById(71L)).thenReturn(task);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.hasEvent(71L, AgentRunModeService.planToBuildKey(71L))).thenReturn(false);
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(901L);
        event.setEventType("RUN_MODE_CHANGED");
        when(lifecycle.appendEvent(any(ExecutionFence.class), org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq("RUN_MODE_CHANGED"), any(),
                org.mockito.ArgumentMatchers.eq(AgentRunModeService.planToBuildKey(71L)))).thenReturn(event);
        AgentRunModeService modeService = new AgentRunModeService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        AgentContext context = context();
        JsonObject args = new JsonObject();
        args.addProperty("plan_summary", "Implement the reviewed plan");

        ToolResult result = new PlanExitTool(modeService).execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        assertThat(context.getMode()).isEqualTo("build");
        assertThat(task.getMode()).isEqualTo("build");
        verify(lifecycle).appendEvent(org.mockito.ArgumentMatchers.eq(new ExecutionFence(71L, "instance-a", 4L)),
                org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq("RUN_MODE_CHANGED"), any(),
                org.mockito.ArgumentMatchers.eq(AgentRunModeService.planToBuildKey(71L)));
    }

    @Test
    void delegatesTheDurableTransitionWithTheStableIdempotencyKey() throws Exception {
        AgentRunModeService modeService = mock(AgentRunModeService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(902L);
        event.setEventType("RUN_MODE_CHANGED");
        when(modeService.transitionPlanToBuild(org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq(new ExecutionFence(71L, "instance-a", 4L)),
                org.mockito.ArgumentMatchers.eq(AgentRunModeService.planToBuildKey(71L)),
                any(AgentContext.class))).thenReturn(event);
        AgentContext context = context();
        JsonObject args = new JsonObject();
        args.addProperty("plan_summary", "Implement the reviewed plan");

        ToolResult result = new PlanExitTool(modeService).execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        verify(modeService).transitionPlanToBuild(org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq(new ExecutionFence(71L, "instance-a", 4L)),
                org.mockito.ArgumentMatchers.eq(AgentRunModeService.planToBuildKey(71L)),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isSameAs(context);
    }

    @Test
    void rejectsExecutionWithoutAnActiveFence() {
        AgentRunModeService modeService = mock(AgentRunModeService.class);
        AgentContext context = context();
        context.setExecutionFence(null);
        JsonObject args = new JsonObject();
        args.addProperty("plan_summary", "Implement the reviewed plan");

        assertThatThrownBy(() -> new PlanExitTool(modeService).execute(context, args))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("invalid-fence");
        verify(modeService, never()).transitionPlanToBuild(any(), any(), any(), any());
    }

    @Test
    void rejectsBlankPlanSummaryBeforeAnyDurableWrite() throws Exception {
        AgentRunModeService modeService = mock(AgentRunModeService.class);
        AgentContext context = context();
        JsonObject args = new JsonObject();
        args.addProperty("plan_summary", "   ");

        ToolResult result = new PlanExitTool(modeService).execute(context, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(context.getMode()).isEqualTo("plan");
        verify(modeService, never()).transitionPlanToBuild(any(), any(), any(), any());
    }

    @Test
    void staleFenceFailsTheToolInsteadOfSwitchingMode() {
        AgentTask task = task();
        task.setExecutionOwner("other-instance");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(task);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunModeService modeService = new AgentRunModeService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        AgentContext context = context();
        JsonObject args = new JsonObject();
        args.addProperty("plan_summary", "Implement the reviewed plan");

        assertThatThrownBy(() -> new PlanExitTool(modeService).execute(context, args))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("stale-owner");
        assertThat(context.getMode()).isEqualTo("plan");
        verify(tasks, never()).update(any(), any());
    }

    private AgentContext context() {
        AgentContext context = new AgentContext("session-1", 7, null, "conversation-1", 71L,
                Path.of("."), new ArrayList<>(), 0);
        context.setMode("plan");
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        return context;
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setMode("plan");
        task.setStatus("running");
        task.setExecutionOwner("instance-a");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        return task;
    }
}
