package com.labex.labexagent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLeaseHeartbeatService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.service.StudentProjectService;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class ManualCompactionTaskRunnerTest {

    @Test
    void createsAnOwnedTaskAndPublishesDurableManualCompactionLifecycleEvents() {
        AgentConversationCompactionService compactions = mock(AgentConversationCompactionService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService heartbeats = mock(AgentRunLeaseHeartbeatService.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        AgentTask task = new AgentTask();
        task.setTaskId(44L);
        task.setSessionId("compact-session");
        task.setStatus("queued");
        when(projects.getOwnedProject(7, 3)).thenReturn(project);
        when(tasks.createTask(eq(7), eq(project), eq("conversation"), any(), eq("compact"), any(), eq(false)))
                .thenReturn(task);
        when(tasks.getOwnedTask(7, 3, 44L)).thenReturn(task);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                44L, "test-worker", 3L, LocalDateTime.now().plusMinutes(1));
        when(leases.acquire(44L)).thenReturn(lease);
        when(compactions.compact(eq(7), eq(3), eq("conversation"), eq(17), eq(task), any(CancellationToken.class)))
                .thenReturn(new AgentConversationCompactionService.Result(
                        "summary", "manual_model", false, 91L, 38L, true));

        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(compactions, tasks, lifecycle, projects,
                new AgentCancellationRegistry(), leases, heartbeats, executor);

        runner.start(7, 3, "conversation", 17);

        verify(lifecycle).appendEvent(eq(44L), eq("COMPACTION_STARTED"), any(), any());
        verify(lifecycle).appendEvent(eq(44L), eq("COMPACTION_PROGRESS"), any(), any());
        verify(lifecycle).appendEvent(eq(44L), eq("COMPACTION_COMPLETED"), any(), any());
        verify(tasks).updateTask(44L, "preparing", "Preparing context compaction", "Preparing checkpoint");
        verify(tasks).updateTask(44L, "running", "Compressing context", "Generating checkpoint");
        verify(tasks).updateTask(44L, "completed", "Context compaction completed", "manual_model");
        verify(heartbeats).track(lease, "compact-session");
        verify(heartbeats).untrack(lease);
        verify(leases).release(lease);
        org.junit.jupiter.api.Assertions.assertEquals(3L, task.getExecutionEpoch());
    }

    @Test
    void doesNotResurrectAQueuedCompactionThatWasCancelledBeforeItsWorkerStarted() {
        AgentConversationCompactionService compactions = mock(AgentConversationCompactionService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService heartbeats = mock(AgentRunLeaseHeartbeatService.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        AgentTask task = new AgentTask();
        task.setTaskId(45L);
        task.setSessionId("compact-cancelled-session");
        task.setStatus("queued");
        AgentTask cancelled = new AgentTask();
        cancelled.setTaskId(45L);
        cancelled.setStatus("cancelled");
        when(projects.getOwnedProject(7, 3)).thenReturn(project);
        when(tasks.createTask(eq(7), eq(project), eq("conversation"), any(), eq("compact"), any(), eq(false)))
                .thenReturn(task);
        when(tasks.getOwnedTask(7, 3, 45L)).thenReturn(cancelled);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(compactions, tasks, lifecycle, projects,
                new AgentCancellationRegistry(), leases, heartbeats, executor);

        runner.start(7, 3, "conversation", null);

        verify(compactions, org.mockito.Mockito.never()).compact(eq(7), eq(3), eq("conversation"),
                org.mockito.ArgumentMatchers.isNull(), eq(task), any(CancellationToken.class));
        verify(tasks, org.mockito.Mockito.never()).updateTask(eq(45L), eq("preparing"), any(), any());
        verify(leases, org.mockito.Mockito.never()).acquire(45L);
    }


    @Test
    void doesNotFailOrResumeATaskOwnedByAnotherWorker() {
        AgentConversationCompactionService compactions = mock(AgentConversationCompactionService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService heartbeats = mock(AgentRunLeaseHeartbeatService.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        AgentTask task = new AgentTask();
        task.setTaskId(46L);
        task.setSessionId("compact-owned-session");
        task.setStatus("queued");
        when(projects.getOwnedProject(7, 3)).thenReturn(project);
        when(tasks.createTask(eq(7), eq(project), eq("conversation"), any(), eq("compact"), any(), eq(false)))
                .thenReturn(task);
        when(tasks.getOwnedTask(7, 3, 46L)).thenReturn(task);
        when(leases.acquire(46L)).thenReturn(null);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(compactions, tasks, lifecycle, projects,
                new AgentCancellationRegistry(), leases, heartbeats, executor);

        runner.start(7, 3, "conversation", null);

        verify(compactions, org.mockito.Mockito.never()).compact(any(), any(), any(), any(), any(), any());
        verify(lifecycle, org.mockito.Mockito.never()).appendEvent(eq(46L), eq("COMPACTION_FAILED"), any(), any());
        verify(tasks, org.mockito.Mockito.never()).updateTask(eq(46L), eq("failed"), any(), any());
        verify(heartbeats, org.mockito.Mockito.never()).track(any(), any());
    }


    @Test
    void leaseContenderDoesNotReplaceTheActiveCancellationTokenOwnedByAnotherWorker() {
        AgentConversationCompactionService compactions = mock(AgentConversationCompactionService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService heartbeats = mock(AgentRunLeaseHeartbeatService.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        AgentTask task = new AgentTask();
        task.setTaskId(47L);
        task.setSessionId("compact-shared-session");
        task.setStatus("queued");
        when(projects.getOwnedProject(7, 3)).thenReturn(project);
        when(tasks.createTask(eq(7), eq(project), eq("conversation"), any(), eq("compact"), any(), eq(false)))
                .thenReturn(task);
        when(tasks.getOwnedTask(7, 3, 47L)).thenReturn(task);
        when(leases.acquire(47L)).thenReturn(null);
        AgentCancellationRegistry cancellations = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun owner = cancellations.register(
                "compact-shared-session", 7, 3, 47L);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(compactions, tasks, lifecycle, projects,
                cancellations, leases, heartbeats, executor);

        runner.start(7, 3, "conversation", null);

        org.junit.jupiter.api.Assertions.assertFalse(owner.isCancellationRequested());
        org.junit.jupiter.api.Assertions.assertSame(owner,
                cancellations.findCancellationTarget("compact-shared-session", 7, 3).activeRun());
    }


    @Test
    void cancellationAfterTheDurableCompactionCommitCannotReverseTheTaskTerminalState() {
        AgentConversationCompactionService compactions = mock(AgentConversationCompactionService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService heartbeats = mock(AgentRunLeaseHeartbeatService.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        AgentTask task = new AgentTask();
        task.setTaskId(48L);
        task.setSessionId("compact-commit-session");
        task.setStatus("queued");
        when(projects.getOwnedProject(7, 3)).thenReturn(project);
        when(tasks.createTask(eq(7), eq(project), eq("conversation"), any(), eq("compact"), any(), eq(false)))
                .thenReturn(task);
        when(tasks.getOwnedTask(7, 3, 48L)).thenReturn(task);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                48L, "test-worker", 4L, LocalDateTime.now().plusMinutes(1));
        when(leases.acquire(48L)).thenReturn(lease);
        AgentCancellationRegistry cancellations = new AgentCancellationRegistry();
        when(compactions.compact(eq(7), eq(3), eq("conversation"), eq(17), eq(task), any(CancellationToken.class)))
                .thenAnswer(invocation -> {
                    cancellations.cancel("compact-commit-session");
                    return new AgentConversationCompactionService.Result(
                            "summary", "manual_model", false, 96L, 42L, true);
                });
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(compactions, tasks, lifecycle, projects,
                cancellations, leases, heartbeats, executor);

        runner.start(7, 3, "conversation", 17);

        verify(lifecycle).appendEvent(eq(48L), eq("COMPACTION_COMPLETED"), any(), any());
        verify(tasks).updateTask(48L, "completed", "Context compaction completed", "manual_model");
        verify(lifecycle, org.mockito.Mockito.never()).appendEvent(eq(48L), eq("COMPACTION_CANCELLED"), any(), any());
        verify(tasks, org.mockito.Mockito.never()).requestCancellation(eq(48L), any(), any());
    }

}
