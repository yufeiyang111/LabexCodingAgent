package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunPlanItem;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunPlanItemMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AgentRunPlanServiceTest {

    @Test
    void replacesTheOrderedPlanAndPublishesOneRevisionedDurableEvent() {
        AgentRunPlanItemMapper plans = Mockito.mock(AgentRunPlanItemMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task(71L, 4L, "running"));
        when(plans.selectByTaskIdOrderByPosition(71L)).thenReturn(List.of());
        when(plans.insertPlanItem(any(AgentRunPlanItem.class))).thenReturn(1);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(19L);
        when(lifecycle.appendEvent(eq(71L), eq("PLAN_UPDATE"), any(), eq("plan-update:71:1")))
                .thenReturn(event);

        AgentRunPlanService.Projection projection = new AgentRunPlanService(plans, tasks, lifecycle).replace(
                71L, 4L, List.of(
                        new AgentRunPlanService.PlanDraft("Inspect", "Read the implementation", false),
                        new AgentRunPlanService.PlanDraft("Verify", "Run the system test", false)),
                "create_plan");

        assertThat(projection.revision()).isEqualTo(1L);
        assertThat(projection.executionEpoch()).isEqualTo(4L);
        assertThat(projection.currentIndex()).isZero();
        assertThat(projection.eventSequence()).isEqualTo(19L);
        assertThat(projection.items()).extracting(AgentRunPlanService.PlanItem::status)
                .containsExactly("in_progress", "pending");

        ArgumentCaptor<AgentRunPlanItem> inserted = ArgumentCaptor.forClass(AgentRunPlanItem.class);
        verify(plans, Mockito.times(2)).insertPlanItem(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(AgentRunPlanItem::getPosition)
                .containsExactly(0, 1);
        assertThat(inserted.getAllValues()).extracting(AgentRunPlanItem::getPlanRevision)
                .containsOnly(1L);
        assertThat(inserted.getAllValues()).extracting(AgentRunPlanItem::getExecutionEpoch)
                .containsOnly(4L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(71L), eq("PLAN_UPDATE"), payload.capture(), eq("plan-update:71:1"));
        assertThat(payload.getValue()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> eventPayload = (Map<String, Object>) payload.getValue();
        assertThat(eventPayload)
                .containsEntry("taskId", 71L)
                .containsEntry("executionEpoch", 4L)
                .containsEntry("planRevision", 1L)
                .containsEntry("source", "create_plan");
    }

    @Test
    void rejectsAStaleExecutionEpochBeforeChangingRowsOrEvents() {
        AgentRunPlanItemMapper plans = Mockito.mock(AgentRunPlanItemMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task(71L, 5L, "running"));

        AgentRunPlanService service = new AgentRunPlanService(plans, tasks, lifecycle);

        assertThatThrownBy(() -> service.replace(71L, 4L,
                List.of(new AgentRunPlanService.PlanDraft("stale", "", false)), "create_plan"))
                .isInstanceOf(AgentRunPlanService.StalePlanEpochException.class)
                .hasMessageContaining("expected epoch 4")
                .hasMessageContaining("current epoch 5");
        verify(plans, never()).deleteByTaskId(any());
        verify(plans, never()).insertPlanItem(any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any());
    }

    @Test
    void restorePrefersDatabaseRowsAndMigratesLegacyCheckpointOnlyWhenDatabaseIsEmpty() {
        AgentRunPlanItemMapper plans = Mockito.mock(AgentRunPlanItemMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        AgentRunPlanService service = new AgentRunPlanService(plans, tasks, lifecycle);
        List<AgentRunPlanService.PlanDraft> legacy = List.of(
                new AgentRunPlanService.PlanDraft("Legacy", "checkpoint", false));
        when(plans.selectByTaskIdOrderByPosition(71L)).thenReturn(List.of(
                row(71L, 0, "Database", "authoritative", "in_progress", 4L, 3L)));

        AgentRunPlanService.Projection database = service.restoreOrMigrate(71L, 5L, legacy);

        assertThat(database.items()).extracting(AgentRunPlanService.PlanItem::title)
                .containsExactly("Database");
        assertThat(database.revision()).isEqualTo(3L);
        verify(tasks, never()).selectByTaskIdForUpdate(71L);
        verify(plans, never()).deleteByTaskId(71L);

        Mockito.reset(plans, tasks, lifecycle);
        when(plans.selectByTaskIdOrderByPosition(71L)).thenReturn(List.of());
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task(71L, 5L, "running"));
        when(plans.insertPlanItem(any(AgentRunPlanItem.class))).thenReturn(1);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(21L);
        when(lifecycle.appendEvent(eq(71L), eq("PLAN_UPDATE"), any(), eq("plan-update:71:1")))
                .thenReturn(event);

        AgentRunPlanService.Projection migrated = service.restoreOrMigrate(71L, 5L, legacy);

        assertThat(migrated.source()).isEqualTo("checkpoint_v1_migration");
        assertThat(migrated.eventSequence()).isEqualTo(21L);
        assertThat(migrated.items()).extracting(AgentRunPlanService.PlanItem::title)
                .containsExactly("Legacy");
    }

    @Test
    void completingAnItemRewritesOneFullSnapshotAndAdvancesTheCurrentItem() {
        AgentRunPlanItemMapper plans = Mockito.mock(AgentRunPlanItemMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task(71L, 6L, "running"));
        when(plans.selectByTaskIdOrderByPosition(71L)).thenReturn(List.of(
                row(71L, 0, "Inspect", "", "in_progress", 5L, 1L),
                row(71L, 1, "Verify", "", "pending", 5L, 1L)));
        when(plans.insertPlanItem(any(AgentRunPlanItem.class))).thenReturn(1);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(20L);
        when(lifecycle.appendEvent(eq(71L), eq("PLAN_UPDATE"), any(), eq("plan-update:71:2")))
                .thenReturn(event);

        AgentRunPlanService.Projection projection = new AgentRunPlanService(plans, tasks, lifecycle)
                .complete(71L, 6L, 0, "create_plan");

        assertThat(projection.revision()).isEqualTo(2L);
        assertThat(projection.currentIndex()).isEqualTo(1);
        assertThat(projection.items()).extracting(AgentRunPlanService.PlanItem::status)
                .containsExactly("completed", "in_progress");
        verify(plans).deleteByTaskId(71L);
        verify(plans, Mockito.times(2)).insertPlanItem(any(AgentRunPlanItem.class));
    }

    private AgentTask task(long taskId, long epoch, String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setConversationId("conversation-71");
        task.setStudentId(7);
        task.setProjectId(12);
        task.setExecutionEpoch(epoch);
        task.setStatus(status);
        return task;
    }

    private AgentRunPlanItem row(long taskId, int position, String title, String description,
                                 String status, long epoch, long revision) {
        AgentRunPlanItem item = new AgentRunPlanItem();
        item.setTaskId(taskId);
        item.setPosition(position);
        item.setTitle(title);
        item.setDescription(description);
        item.setStatus(status);
        item.setExecutionEpoch(epoch);
        item.setPlanRevision(revision);
        return item;
    }
}
