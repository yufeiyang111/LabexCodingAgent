package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentCheckpointStore;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentRunProgressProjectionServiceTest {

    @Test
    void rebuildsProgressLastToolAndRunLogOnlyFromDurablePartsAndEvents() {
        AgentRunPartMapper parts = Mockito.mock(AgentRunPartMapper.class);
        AgentRunEventMapper events = Mockito.mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectById(71L)).thenReturn(task(71L, 4L, "running"));
        when(parts.selectList(any())).thenReturn(List.of(
                toolPart(1L, "call-write", "write_file", "completed",
                        "{\"file_path\":\"src/Main.java\"}", "saved"),
                toolPart(2L, "call-tests", "run_tests", "completed", "{}", "exit=0")));
        when(events.selectList(any())).thenReturn(List.of(sessionEvent(3L, ".labex/agent-logs/run.md")));

        AgentRunProgressProjectionService.Projection projection = service(parts, events, tasks, lifecycle)
                .load(71L, 4L);

        assertThat(projection.stage()).isEqualTo("verify");
        assertThat(projection.writeCount()).isEqualTo(1);
        assertThat(projection.verificationCount()).isEqualTo(1);
        assertThat(projection.trustedVerificationSources()).containsExactly("run_tests");
        assertThat(projection.unverifiedChangeTargets()).isEmpty();
        assertThat(projection.lastToolCallId()).isEqualTo("call-tests");
        assertThat(projection.lastTool()).isEqualTo("run_tests");
        assertThat(projection.lastResult()).isEqualTo("exit=0");
        assertThat(projection.runLogPath()).isEqualTo(".labex/agent-logs/run.md");
        assertThat(projection.renderForPrompt()).contains("authority: agent_run_part_event");
    }

    @Test
    void rebuildsNonzeroShellOutcomeAsRepairFromDurablePartMetadata() {
        AgentRunPartMapper parts = Mockito.mock(AgentRunPartMapper.class);
        AgentRunEventMapper events = Mockito.mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectById(71L)).thenReturn(task(71L, 4L, "running"));
        AgentRunPart write = toolPart(1L, "call-write", "write_file", "completed",
                "{\"file_path\":\"src/Main.java\"}", "saved");
        AgentRunPart shell = toolPart(2L, "call-shell", "shell", "completed", "{\"command\":\"npm run build\"}",
                "exit=2\nstatus=failed");
        shell.setMetadata("{\"failureClass\":\"non_zero_exit\",\"execution\":{\"status\":\"failed\",\"exitCode\":2}}");
        when(parts.selectList(any())).thenReturn(List.of(write, shell));
        when(events.selectList(any())).thenReturn(List.of());

        AgentRunProgressProjectionService.Projection projection = service(parts, events, tasks, lifecycle)
                .load(71L, 4L);

        assertThat(projection.stage()).isEqualTo("repair");
        assertThat(projection.writeCount()).isEqualTo(1);
        assertThat(projection.verificationCount()).isZero();
        assertThat(projection.lastStatus()).isEqualTo("error");
        assertThat(projection.lastResult()).contains("exit=2");
    }

    @Test
    void rebuildsWorkspacePostconditionMismatchAsRepairFromDurablePartMetadata() {
        AgentRunPartMapper parts = Mockito.mock(AgentRunPartMapper.class);
        AgentRunEventMapper events = Mockito.mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectById(71L)).thenReturn(task(71L, 4L, "running"));
        AgentRunPart shell = toolPart(1L, "call-shell", "shell", "completed", "{\"command\":\"rm skills/SKILL.md\"}",
                "exit=0");
        shell.setMetadata("""
                {"workspaceMutation":{"state":"applied","targets":[{"path":"skills/SKILL.md","operation":"delete","after":{"state":"present","verified":false}}]},
                 "workspaceVerification":{"state":"mismatch","targets":[{"path":"skills/SKILL.md","expectedState":"absent","observedState":"present"}]}}
                """);
        when(parts.selectList(any())).thenReturn(List.of(shell));
        when(events.selectList(any())).thenReturn(List.of());

        AgentRunProgressProjectionService.Projection projection = service(parts, events, tasks, lifecycle)
                .load(71L, 4L);

        assertThat(projection.stage()).isEqualTo("repair");
        assertThat(projection.writeCount()).isEqualTo(1);
        assertThat(projection.verificationCount()).isZero();
        assertThat(projection.trustedVerificationSources()).isEmpty();
        assertThat(projection.unverifiedChangeTargets()).containsExactly("skills/SKILL.md");
        assertThat(projection.unverifiedChanges()).isTrue();
    }

    @Test
    void durableToolPartsWinOverAStaleLegacyCheckpointSeed() {
        AgentRunPartMapper parts = Mockito.mock(AgentRunPartMapper.class);
        AgentRunEventMapper events = Mockito.mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectById(71L)).thenReturn(task(71L, 4L, "running"));
        when(parts.selectList(any())).thenReturn(List.of(
                toolPart(1L, "call-write", "write_file", "completed",
                        "{\"file_path\":\"durable.txt\"}", "saved")));
        when(events.selectList(any())).thenReturn(List.of());
        AgentCheckpointStore.LegacyExecutionSeed stale = new AgentCheckpointStore.LegacyExecutionSeed(
                "verify", 99, 88, false, Set.of("stale"), Set.of(),
                "stale_tool", "stale_result", "stale.log", "stale note");

        AgentRunProgressProjectionService.Projection projection = service(parts, events, tasks, lifecycle)
                .restoreOrMigrate(71L, 4L, stale);

        assertThat(projection.writeCount()).isEqualTo(1);
        assertThat(projection.verificationCount()).isZero();
        assertThat(projection.lastTool()).isEqualTo("write_file");
        verify(lifecycle, never()).appendEvent(eq(71L), eq("RUN_PROGRESS_MIGRATED"), any(), any());
    }

    @Test
    void migratesLegacyExecutionStateOnceAsADurableEpochFencedEvent() {
        AgentRunPartMapper parts = Mockito.mock(AgentRunPartMapper.class);
        AgentRunEventMapper events = Mockito.mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        AgentTask task = task(71L, 4L, "running");
        when(tasks.selectById(71L)).thenReturn(task);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(parts.selectList(any())).thenReturn(List.of());
        when(events.selectList(any())).thenReturn(List.of());
        AgentRunEvent migrated = new AgentRunEvent();
        migrated.setSequenceNumber(9L);
        when(lifecycle.appendEvent(eq(71L), eq("RUN_PROGRESS_MIGRATED"), any(),
                eq("run-progress-migration:71"))).thenReturn(migrated);
        AgentCheckpointStore.LegacyExecutionSeed seed = new AgentCheckpointStore.LegacyExecutionSeed(
                "implement", 2, 0, true, Set.of(), Set.of("src/Main.java"),
                "edit_file", "saved", ".labex/agent-logs/legacy.md", "resume safely");

        AgentRunProgressProjectionService.Projection projection = service(parts, events, tasks, lifecycle)
                .restoreOrMigrate(71L, 4L, seed);

        assertThat(projection.source()).isEqualTo("legacy_checkpoint_migration");
        assertThat(projection.eventSequence()).isEqualTo(9L);
        assertThat(projection.writeCount()).isEqualTo(2);
        assertThat(projection.unverifiedChangeTargets()).containsExactly("src/Main.java");
        verify(lifecycle).appendEvent(eq(71L), eq("RUN_PROGRESS_MIGRATED"), any(),
                eq("run-progress-migration:71"));
    }

    @Test
    void rejectsLegacyMigrationFromAStaleExecutionEpoch() {
        AgentRunPartMapper parts = Mockito.mock(AgentRunPartMapper.class);
        AgentRunEventMapper events = Mockito.mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = Mockito.mock(AgentRunLifecycleService.class);
        when(tasks.selectById(71L)).thenReturn(task(71L, 5L, "running"));
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task(71L, 5L, "running"));
        when(parts.selectList(any())).thenReturn(List.of());
        when(events.selectList(any())).thenReturn(List.of());
        AgentCheckpointStore.LegacyExecutionSeed seed = new AgentCheckpointStore.LegacyExecutionSeed(
                "implement", 1, 0, true, Set.of(), Set.of("src/Main.java"),
                "write_file", "saved", "", "");

        assertThatThrownBy(() -> service(parts, events, tasks, lifecycle)
                .restoreOrMigrate(71L, 4L, seed))
                .isInstanceOf(AgentRunProgressProjectionService.StaleProgressEpochException.class)
                .hasMessageContaining("expected epoch 4");
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any());
    }

    private AgentRunProgressProjectionService service(AgentRunPartMapper parts, AgentRunEventMapper events,
                                                       AgentTaskMapper tasks, AgentRunLifecycleService lifecycle) {
        return new AgentRunProgressProjectionService(parts, events, tasks, lifecycle,
                new AgentRunExecutionProgressReducer());
    }

    private AgentTask task(long taskId, long epoch, String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setExecutionEpoch(epoch);
        task.setStatus(status);
        task.setCurrentStep("Working");
        task.setSummary("Durable summary");
        return task;
    }

    private AgentRunPart toolPart(long partId, String callId, String tool, String status,
                                  String input, String output) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(partId);
        part.setTaskId(71L);
        part.setPartType("tool");
        part.setToolCallId(callId);
        part.setToolName(tool);
        part.setStatus(status);
        part.setInputJson(input);
        part.setOutputText(output);
        part.setCreateTime(LocalDateTime.now());
        return part;
    }

    private AgentRunEvent sessionEvent(long sequence, String logPath) {
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(71L);
        event.setSequenceNumber(sequence);
        event.setEventType("SESSION");
        event.setPayload("{\"logPath\":\"" + logPath + "\"}");
        return event;
    }
}