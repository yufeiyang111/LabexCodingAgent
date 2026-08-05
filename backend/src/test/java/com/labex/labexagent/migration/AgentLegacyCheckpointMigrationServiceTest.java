package com.labex.labexagent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.AgentRunProgressProjectionService;
import com.labex.labexagent.runtime.AgentCheckpointStore;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.mapper.AgentRunEventMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentLegacyCheckpointMigrationServiceTest {

    @Test
    void firstResumeReadsAndMigratesCheckpointThenPersistsAnIdempotentInspectionMarker() {
        Fixture fixture = fixture();
        when(fixture.events.selectCount(any())).thenReturn(0L);
        AgentCheckpointStore.Snapshot snapshot = mock(AgentCheckpointStore.Snapshot.class);
        AgentCheckpointStore.LegacyExecutionSeed execution = new AgentCheckpointStore.LegacyExecutionSeed(
                "verify", 1, 1, false, Set.of("run_tests"), Set.of(),
                "run_tests", "passed", ".labex/agent-logs/run.md", "resume");
        AgentCheckpointStore.LegacyPlanSeed plan = new AgentCheckpointStore.LegacyPlanSeed(
                List.of(new AgentContext.PlanItem("Inspect", "Read state", true)), 0);
        when(snapshot.version()).thenReturn(2);
        when(snapshot.legacyExecutionSeed()).thenReturn(Optional.of(execution));
        when(snapshot.legacyPlanSeed()).thenReturn(Optional.of(plan));
        when(fixture.store.loadLegacy(fixture.project, "conversation-a", 41L)).thenReturn(Optional.of(snapshot));
        when(fixture.progress.restoreOrMigrate(41L, 7L, execution)).thenReturn(fixture.progressProjection);
        when(fixture.plans.restoreOrMigrate(eq(41L), eq(7L), any())).thenReturn(fixture.planProjection);
        AgentRunEvent marker = new AgentRunEvent();
        marker.setTaskId(41L);
        marker.setSequenceNumber(19L);
        when(fixture.lifecycle.appendEvent(eq(41L), eq(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT),
                any(), eq("legacy-checkpoint-inspected:41"))).thenReturn(marker);

        AgentLegacyCheckpointMigrationService.RestoreResult result = fixture.service.restoreOrMigrate(
                fixture.project, "conversation-a", fixture.task, 7L, true);

        assertThat(result.inspectionEventSequence()).isEqualTo(19L);
        verify(fixture.gates).recordReaderHit(AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER, 1L);
        verify(fixture.store).loadLegacy(fixture.project, "conversation-a", 41L);
        verify(fixture.lifecycle).appendEvent(eq(41L),
                eq(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT), any(),
                eq("legacy-checkpoint-inspected:41"));
    }

    @Test
    void durableInspectionMarkerPreventsEveryLaterResumeFromReadingTheLegacyFileAgain() {
        Fixture fixture = fixture();
        when(fixture.events.selectCount(any())).thenReturn(1L);
        when(fixture.progress.restoreOrMigrate(41L, 7L, null)).thenReturn(fixture.progressProjection);
        when(fixture.plans.restoreOrMigrate(41L, 7L, List.of())).thenReturn(fixture.planProjection);

        AgentLegacyCheckpointMigrationService.RestoreResult result = fixture.service.restoreOrMigrate(
                fixture.project, "conversation-a", fixture.task, 7L, true);

        assertThat(result.inspectionEventSequence()).isZero();
        verify(fixture.store, never()).loadLegacy(any(), any(), any());
        verify(fixture.gates, never()).recordReaderHit(any(), anyLong());
        verify(fixture.lifecycle, never()).appendEvent(eq(41L),
                eq(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT), any(), any());
    }

    @Test
    void absentCheckpointStillPersistsInspectionMarkerWithoutRecordingAReaderHit() {
        Fixture fixture = fixture();
        when(fixture.events.selectCount(any())).thenReturn(0L);
        when(fixture.store.loadLegacy(fixture.project, "conversation-a", 41L)).thenReturn(Optional.empty());
        when(fixture.progress.restoreOrMigrate(41L, 7L, null)).thenReturn(fixture.progressProjection);
        when(fixture.plans.restoreOrMigrate(41L, 7L, List.of())).thenReturn(fixture.planProjection);
        AgentRunEvent marker = new AgentRunEvent();
        marker.setTaskId(41L);
        marker.setSequenceNumber(23L);
        when(fixture.lifecycle.appendEvent(eq(41L), eq(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT),
                any(), eq("legacy-checkpoint-inspected:41"))).thenReturn(marker);

        AgentLegacyCheckpointMigrationService.RestoreResult result = fixture.service.restoreOrMigrate(
                fixture.project, "conversation-a", fixture.task, 7L, true);

        assertThat(result.inspectionEventSequence()).isEqualTo(23L);
        verify(fixture.gates, never()).recordReaderHit(any(), anyLong());
        verify(fixture.lifecycle).appendEvent(eq(41L),
                eq(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT), any(),
                eq("legacy-checkpoint-inspected:41"));
    }

    @Test
    void corruptCheckpointDoesNotPersistInspectionMarkerAndCanBeRetriedAfterRepair() {
        Fixture fixture = fixture();
        when(fixture.events.selectCount(any())).thenReturn(0L);
        when(fixture.store.loadLegacy(fixture.project, "conversation-a", 41L))
                .thenThrow(new IllegalStateException("Unable to read legacy task checkpoint"));

        assertThatThrownBy(() -> fixture.service.restoreOrMigrate(
                fixture.project, "conversation-a", fixture.task, 7L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy task checkpoint");

        verify(fixture.gates, never()).recordReaderHit(any(), anyLong());
        verify(fixture.lifecycle, never()).appendEvent(eq(41L),
                eq(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT), any(), any());
        verify(fixture.progress, never()).restoreOrMigrate(anyLong(), anyLong(), any());
        verify(fixture.plans, never()).restoreOrMigrate(anyLong(), anyLong(), any());
    }

    private Fixture fixture() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        AgentRunProgressProjectionService progress = mock(AgentRunProgressProjectionService.class);
        AgentRunPlanService plans = mock(AgentRunPlanService.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLegacyMigrationGateService gates = mock(AgentLegacyMigrationGateService.class);
        AgentRunProgressProjectionService.Projection progressProjection =
                mock(AgentRunProgressProjectionService.Projection.class);
        AgentRunPlanService.Projection planProjection = mock(AgentRunPlanService.Projection.class);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath("D:/workspace");
        AgentTask task = new AgentTask();
        task.setTaskId(41L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-a");
        AgentLegacyCheckpointMigrationService service = new AgentLegacyCheckpointMigrationService(
                store, progress, plans, events, lifecycle, gates);
        return new Fixture(service, store, progress, plans, events, lifecycle, gates,
                progressProjection, planProjection, project, task);
    }

    private record Fixture(AgentLegacyCheckpointMigrationService service,
                           AgentCheckpointStore store,
                           AgentRunProgressProjectionService progress,
                           AgentRunPlanService plans,
                           AgentRunEventMapper events,
                           AgentRunLifecycleService lifecycle,
                           AgentLegacyMigrationGateService gates,
                           AgentRunProgressProjectionService.Projection progressProjection,
                           AgentRunPlanService.Projection planProjection,
                           StudentProject project,
                           AgentTask task) {
    }
}
