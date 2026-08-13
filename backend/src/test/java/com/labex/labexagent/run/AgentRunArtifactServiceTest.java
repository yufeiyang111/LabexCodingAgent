package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunArtifact;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunExecutionLeaseService.StaleExecutionFenceException;
import com.labex.mapper.AgentRunArtifactMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentRunArtifactServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 23, 10, 0);

    @Test
    void deterministicRecordReusesArtifactWithSameHash() {
        AgentRunArtifactMapper mapper = Mockito.mock(AgentRunArtifactMapper.class);
        AgentRunArtifact existing = new AgentRunArtifact();
        existing.setSha256(AgentRunArtifactService.sha256("payload"));
        when(mapper.selectOne(any())).thenReturn(existing);

        AgentRunArtifact result = new AgentRunArtifactService(mapper)
                .recordDeterministic(7L, "completion_evidence", "run", "payload");

        assertTrue(result == existing);
        verify(mapper, never()).insert(any());
    }

    @Test
    void fencedRecordRejectsStaleOwnerBeforeWritingArtifact() {
        AgentRunArtifactMapper mapper = mock(AgentRunArtifactMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-b", 4L, NOW.plusMinutes(1)));

        AgentRunArtifactService service = new AgentRunArtifactService(mapper,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.record(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "tool_failure", "run_tests", "status=failed"));

        assertEquals(StaleExecutionFenceException.Reason.STALE_OWNER, error.reason());
        verify(mapper, never()).insert(any());
    }

    @Test
    void fencedRecordRejectsStaleEpochBeforeWritingArtifact() {
        AgentRunArtifactMapper mapper = mock(AgentRunArtifactMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-a", 4L, NOW.plusMinutes(1)));

        AgentRunArtifactService service = new AgentRunArtifactService(mapper,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.record(new ExecutionFence(7L, "instance-a", 3L),
                        7L, "tool_failure", "run_tests", "status=failed"));

        assertEquals(StaleExecutionFenceException.Reason.STALE_EPOCH, error.reason());
        verify(mapper, never()).insert(any());
    }

    @Test
    void fencedRecordRejectsExpiredLeaseBeforeWritingArtifact() {
        AgentRunArtifactMapper mapper = mock(AgentRunArtifactMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-a", 4L, NOW.minusSeconds(1)));

        AgentRunArtifactService service = new AgentRunArtifactService(mapper,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.record(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "tool_failure", "run_tests", "status=failed"));

        assertEquals(StaleExecutionFenceException.Reason.EXPIRED_LEASE, error.reason());
        verify(mapper, never()).insert(any());
    }

    @Test
    void fencedRecordPersistsArtifactWhenTheFenceIsActive() {
        AgentRunArtifactMapper mapper = mock(AgentRunArtifactMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(mapper.insert(any(AgentRunArtifact.class))).thenAnswer(invocation -> {
            AgentRunArtifact artifact = invocation.getArgument(0);
            artifact.setArtifactId(31L);
            return 1;
        });

        AgentRunArtifact artifact = new AgentRunArtifactService(mapper,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L))
                .record(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "tool_failure", "run_tests", "status=failed");

        assertEquals(31L, artifact.getArtifactId());
        assertEquals("tool_failure", artifact.getArtifactType());
        verify(mapper).insert(any(AgentRunArtifact.class));
    }

    @Test
    void fencedToolOutputRecordStoresWorkspacePathAndStructuredSummary() {
        AgentRunArtifactMapper mapper = mock(AgentRunArtifactMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(mapper.insert(any(AgentRunArtifact.class))).thenAnswer(invocation -> {
            AgentRunArtifact artifact = invocation.getArgument(0);
            artifact.setArtifactId(52L);
            return 1;
        });

        AgentRunArtifact artifact = new AgentRunArtifactService(mapper,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L))
                .recordToolOutput(new ExecutionFence(7L, "instance-a", 4L), 7L,
                        "shell", "call-1", ".labex-agent/artifacts/task-7/call-1.log",
                        "bash", "frontend", "failed", 2, 321L, true, 15_000L);

        assertEquals(52L, artifact.getArtifactId());
        assertEquals("tool_output", artifact.getArtifactType());
        assertEquals(".labex-agent/artifacts/task-7/call-1.log", artifact.getArtifactPath());
        assertTrue(artifact.getContent().contains("tool=shell"));
        assertTrue(artifact.getContent().contains("output_chars=15000"));
        verify(mapper).insert(any(AgentRunArtifact.class));
    }

    @Test
    void rejectedFencedRecordLeaksNoSentinelIntoArtifactOrErrorPayload() {
        String sentinel = "SENTINEL-SECRET-8c11d2";
        AgentRunArtifactMapper mapper = mock(AgentRunArtifactMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-b", 4L, NOW.plusMinutes(1)));

        AgentRunArtifactService service = new AgentRunArtifactService(mapper,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.record(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "tool_failure", "run_tests", "token=" + sentinel));

        assertTrue(!error.getMessage().contains(sentinel));
        verify(mapper, never()).insert(any());
    }

    private AgentTask task(String owner, long epoch, LocalDateTime leaseExpiresAt) {
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(leaseExpiresAt);
        return task;
    }
}
