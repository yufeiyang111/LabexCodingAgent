package com.labex.labexagent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.service.StudentProjectService;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class ManualCompactionTaskRunnerTest {

    @Test
    void createsAnOwnedTaskAndPublishesDurableManualCompactionLifecycleEvents() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
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
        when(conversations.compactConversation(eq(7), eq(3), eq("conversation"), eq(17), any(CancellationToken.class)))
                .thenReturn(new AgentConversationService.ManualCompactionResult("summary", "manual_model", false));

        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(conversations, tasks, lifecycle, projects,
                new AgentCancellationRegistry(), executor);

        runner.start(7, 3, "conversation", 17);

        verify(lifecycle).appendEvent(eq(44L), eq("COMPACTION_STARTED"), any(), any());
        verify(lifecycle).appendEvent(eq(44L), eq("COMPACTION_PROGRESS"), any(), any());
        verify(lifecycle).appendEvent(eq(44L), eq("COMPACTION_COMPLETED"), any(), any());
        verify(tasks).updateTask(44L, "preparing", "Preparing context compaction", "Preparing checkpoint");
        verify(tasks).updateTask(44L, "running", "Compressing context", "Generating checkpoint");
        verify(tasks).updateTask(44L, "completed", "Context compaction completed", "manual_model");
    }

    @Test
    void doesNotResurrectAQueuedCompactionThatWasCancelledBeforeItsWorkerStarted() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
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
        ManualCompactionTaskRunner runner = new ManualCompactionTaskRunner(conversations, tasks, lifecycle, projects,
                new AgentCancellationRegistry(), executor);

        runner.start(7, 3, "conversation", null);

        verify(conversations, org.mockito.Mockito.never()).compactConversation(eq(7), eq(3), eq("conversation"),
                org.mockito.ArgumentMatchers.isNull(), any(CancellationToken.class));
        verify(tasks, org.mockito.Mockito.never()).updateTask(eq(45L), eq("preparing"), any(), any());
    }

}
