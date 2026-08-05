package com.labex.labexagent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentCheckpointStore;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.StudentProjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentLegacyMigrationReadinessServiceTest {

    @Test
    void reportsPendingHistoryAndRejectsInspectedCheckpointOwnedByAnotherTaskBoundary() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        StudentProjectMapper projects = mock(StudentProjectMapper.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentCheckpointStore checkpoints = mock(AgentCheckpointStore.class);
        AgentLegacyMigrationGateService gates = mock(AgentLegacyMigrationGateService.class);
        when(conversations.countActiveHistorySources()).thenReturn(5L);
        when(conversations.countPendingHistorySources("durable-v1")).thenReturn(1L);
        when(messages.countRetainedLegacyRows()).thenReturn(20L);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath("D:/workspace");
        when(projects.selectActiveProjects()).thenReturn(List.of(project));
        when(checkpoints.scanLegacySources(any())).thenReturn(new AgentCheckpointStore.LegacyInventory(
                List.of(
                        new AgentCheckpointStore.LegacySource(7, 12, "conversation-a", 41L, 1),
                        new AgentCheckpointStore.LegacySource(7, 12, "conversation-b", 42L, 2)),
                1L, 0L, false));
        when(events.selectDistinctTaskIdsByEventType(AgentLegacyCheckpointMigrationService.INSPECTION_EVENT))
                .thenReturn(List.of(41L, 42L));
        AgentTask owned = task(41L, 7, 12, "conversation-a");
        AgentTask mismatched = task(42L, 7, 99, "conversation-b");
        when(tasks.selectBatchIds(any())).thenReturn(List.of(owned, mismatched));
        when(gates.refreshInventory(AgentLegacyMigrationGateService.LEGACY_HISTORY_READER, 1L))
                .thenReturn(blockedGate(AgentLegacyMigrationGateService.LEGACY_HISTORY_READER, 1L));
        when(gates.refreshInventory(AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER, 2L))
                .thenReturn(blockedGate(AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER, 2L));
        Clock clock = Clock.fixed(Instant.parse("2026-08-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        AgentLegacyMigrationReadinessService service = new AgentLegacyMigrationReadinessService(
                conversations, messages, projects, events, tasks, checkpoints, gates, clock);

        AgentLegacyMigrationReadinessService.ReadinessReport report = service.refresh();

        assertThat(report.readyForRemoval()).isFalse();
        AgentLegacyMigrationReadinessService.ReaderReadiness history =
                report.reader(AgentLegacyMigrationGateService.LEGACY_HISTORY_READER);
        assertThat(history.totalSources()).isEqualTo(5L);
        assertThat(history.pendingSources()).isEqualTo(1L);
        assertThat(history.coveredSources()).isEqualTo(4L);
        assertThat(history.retainedItems()).isEqualTo(20L);
        AgentLegacyMigrationReadinessService.ReaderReadiness checkpoint =
                report.reader(AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER);
        assertThat(checkpoint.totalSources()).isEqualTo(3L);
        assertThat(checkpoint.pendingSources()).isEqualTo(2L);
        assertThat(checkpoint.coveredSources()).isEqualTo(1L);
        assertThat(checkpoint.unownedSources()).isEqualTo(1L);
        assertThat(checkpoint.invalidSources()).isEqualTo(1L);
        assertThat(checkpoint.unscannableProjects()).isZero();
        assertThat(checkpoint.inventoryTruncated()).isFalse();
    }

    private AgentTask task(long taskId, int studentId, int projectId, String conversationId) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setStudentId(studentId);
        task.setProjectId(projectId);
        task.setConversationId(conversationId);
        return task;
    }

    private AgentLegacyMigrationGateService.GateSnapshot blockedGate(String reader, long pending) {
        return new AgentLegacyMigrationGateService.GateSnapshot(
                reader, "1.1.0", 14, 0L, 0L, null,
                pending, null, null, null, false, List.of("pending_sources"));
    }
}
