package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.run.BackgroundRunWorktreeService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AgentTaskServiceTimingTest {

    @Test
    void createsTaskWithServerSubmittedTime() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        doAnswer(invocation -> {
            invocation.<AgentTask>getArgument(0).setTaskId(72L);
            return 1;
        }).when(taskMapper).insert(any(AgentTask.class));
        AgentTaskService service = service(taskMapper);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        LocalDateTime submittedAt = LocalDateTime.of(2026, 7, 18, 10, 0, 0, 123_000_000);

        AgentTask task = service.createTask(7, project, "conversation-1", "session-1", "agent", "Inspect timing", false, submittedAt);

        assertEquals(submittedAt, task.getSubmittedAt());
        assertNull(task.getStartedAt());
        assertEquals(0L, task.getActiveElapsedMs());
    }

    @Test
    void accumulatesActiveTimeAcrossPauseAndResumeThenFinalizesTerminalTask() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask task = task("running", LocalDateTime.of(2026, 7, 18, 10, 0));
        when(taskMapper.selectById(72L)).thenReturn(task);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentTaskService service = service(taskMapper);
        LocalDateTime firstStart = LocalDateTime.of(2026, 7, 18, 10, 0, 2);

        service.startTiming(72L, firstStart);
        service.pauseTiming(72L, firstStart.plusSeconds(5));
        service.startTiming(72L, firstStart.plusSeconds(8));
        task.setStatus("completed");
        service.finishTimingIfTerminal(72L, firstStart.plusSeconds(12));

        assertEquals(firstStart, task.getStartedAt());
        assertEquals(firstStart.plusSeconds(12), task.getFinishedAt());
        assertNull(task.getActiveSegmentStartedAt());
        assertEquals(9_000L, task.getActiveElapsedMs());
        assertEquals(14_000L, task.getElapsedMs());
        verify(taskMapper, times(4)).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void doesNotFinalizeNonTerminalTask() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask task = task("waiting_user", LocalDateTime.of(2026, 7, 18, 10, 0));
        when(taskMapper.selectById(72L)).thenReturn(task);
        AgentTaskService service = service(taskMapper);

        service.finishTimingIfTerminal(72L, LocalDateTime.of(2026, 7, 18, 10, 1));

        assertNull(task.getFinishedAt());
        verify(taskMapper, times(0)).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    private AgentTaskService service(AgentTaskMapper taskMapper) {
        return newTaskService(taskMapper, mock(AgentChangeSetMapper.class), mock(AgentFileChangeMapper.class));
    }

    private AgentTask task(String status, LocalDateTime submittedAt) {
        AgentTask task = new AgentTask();
        task.setTaskId(72L);
        task.setStatus(status);
        task.setSubmittedAt(submittedAt);
        task.setActiveElapsedMs(0L);
        return task;
    }

    private AgentTaskService newTaskService(AgentTaskMapper taskMapper,
                                            AgentChangeSetMapper changeSetMapper,
                                            AgentFileChangeMapper fileChangeMapper) {
        return new AgentTaskService(taskMapper, changeSetMapper, fileChangeMapper,
                mock(AgentRunLifecycleService.class), mock(AgentRunExecutionLeaseService.class),
                mock(BackgroundRunWorktreeService.class));
    }
}
