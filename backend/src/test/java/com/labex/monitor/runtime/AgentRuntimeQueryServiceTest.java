package com.labex.monitor.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeQueryServiceTest {

    private final AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
    private final AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
    private final AgentModelConfigService configService = mock(AgentModelConfigService.class);
    private final AgentRunInteractionService interactionService = mock(AgentRunInteractionService.class);
    private final AgentRuntimeProjectionService projection = mock(AgentRuntimeProjectionService.class);
    private final MonitorRuntimeProperties properties = new MonitorRuntimeProperties();

    private AgentRuntimeQueryService service() {
        return new AgentRuntimeQueryService(taskMapper, eventMapper, configService, interactionService,
                projection, properties);
    }

    private AgentTask task(Long id, String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(id);
        task.setConversationId("conv-" + id);
        task.setStatus(status);
        task.setModelConfigId(7);
        return task;
    }

    private AgentRuntimeTaskSummary summary(Long id) {
        return new AgentRuntimeTaskSummary(id, "conv-" + id, "s" + id, "build", "running", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test
    void listTasksReturnsPagedItems() {
        AgentRuntimeQueryService service = service();
        when(taskMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<AgentTask> page = inv.getArgument(0);
            page.setRecords(List.of(task(1L, "running")));
            page.setTotal(21L);
            return page;
        });
        when(projection.toSummary(any(), any(), any(), any(), anyBoolean())).thenReturn(summary(1L));
        when(configService.listByIds(List.of(7))).thenReturn(List.of(config("deepseek", "deepseek-chat")));

        AgentRuntimePage<AgentRuntimeTaskSummary> result =
                service.listTasks(new AgentRuntimeQueryFilter(null, null, null, false, 1, 20));

        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(21);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void listTasksWithProviderFiltersThroughConfigIds() {
        AgentRuntimeQueryService service = service();
        when(taskMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<AgentTask> page = inv.getArgument(0);
            page.setRecords(List.of(task(1L, "running")));
            page.setTotal(1L);
            return page;
        });
        when(configService.listConfigIdsByProvider("deepseek")).thenReturn(List.of(7, 8));
        when(configService.listByIds(List.of(7))).thenReturn(List.of(config("deepseek", "deepseek-chat")));
        when(projection.toSummary(any(), any(), any(), any(), anyBoolean())).thenReturn(summary(1L));

        service.listTasks(new AgentRuntimeQueryFilter(null, null, "deepseek", false, 1, 20));

        verify(configService).listConfigIdsByProvider("deepseek");
    }

    @Test
    void listTasksEmptyResult() {
        AgentRuntimeQueryService service = service();
        when(taskMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<AgentTask> page = inv.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });

        AgentRuntimePage<AgentRuntimeTaskSummary> result =
                service.listTasks(new AgentRuntimeQueryFilter(null, null, null, false, 1, 20));

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.total()).isZero();
    }

    @Test
    void taskDetailReturnsNullWhenTaskMissing() {
        AgentRuntimeQueryService service = service();
        when(taskMapper.selectById(999L)).thenReturn(null);

        assertThat(service.taskDetail(999L)).isNull();
    }

    @Test
    void taskDetailAssemblesDetail() {
        AgentRuntimeQueryService service = service();
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "waiting_approval"));
        AgentModelConfig config = config("qwen", "qwen-max");
        when(configService.getById(7)).thenReturn(config);
        AgentRunEvent latest = new AgentRunEvent();
        latest.setEventType("tool_executed");
        when(eventMapper.selectLatestByTaskId(1L)).thenReturn(latest);
        AgentRunInteraction waiting = new AgentRunInteraction();
        waiting.setInteractionId("i1");
        waiting.setInteractionType("permission");
        when(interactionService.findWaitingForTask(1L)).thenReturn(waiting);
        AgentRuntimeTaskDetail detail = new AgentRuntimeTaskDetail(1L, "conv-1", "s1", "build",
                "waiting_approval", null, null, null, null, null, null, null, null, null, null, null,
                "qwen", "qwen-max", null, null, "tool_executed", null, "等待权限审批", "permission", "i1");
        when(projection.toDetail(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(detail);

        AgentRuntimeTaskDetail result = service.taskDetail(1L);

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("qwen");
        verify(eventMapper).selectLatestByTaskId(1L);
        verify(interactionService).findWaitingForTask(1L);
    }

    @Test
    void timelineReturnsCursorPagedEvents() {
        AgentRuntimeQueryService service = service();
        AgentRunEvent e1 = event(1L, 1L, "task_initialized");
        AgentRunEvent e2 = event(2L, 2L, "tool_executed");
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e1, e2));
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(projection.toEventItem(any(), anyInt()))
                .thenAnswer(inv -> new AgentRuntimeEventItem(
                        ((AgentRunEvent) inv.getArgument(0)).getEventId(),
                        ((AgentRunEvent) inv.getArgument(0)).getSequenceNumber(),
                        ((AgentRunEvent) inv.getArgument(0)).getEventType(),
                        "running", null, null));

        AgentRuntimePage<AgentRuntimeEventItem> result = service.taskTimeline(1L, null, 0);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).eventType()).isEqualTo("task_initialized");
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void timelineEmptyWhenNoEvents() {
        AgentRuntimeQueryService service = service();
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        AgentRuntimePage<AgentRuntimeEventItem> result = service.taskTimeline(1L, null, 0);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void timelineClampsLimitToConfiguredMaximum() {
        AgentRuntimeQueryService service = service();
        properties.setEventTimelineMaxLimit(50);
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.taskTimeline(1L, null, 99999);

        assertThat(properties.getEventTimelineMaxLimit()).isEqualTo(50);
    }

    private static AgentRunEvent event(Long id, Long seq, String type) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(id);
        event.setSequenceNumber(seq);
        event.setEventType(type);
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private static AgentModelConfig config(String provider, String modelName) {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(7);
        config.setProvider(provider);
        config.setModelName(modelName);
        return config;
    }
}
