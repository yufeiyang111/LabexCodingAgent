package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.workspace.ProjectCheckoutLeaseService;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkspaceAdmissionSchedulerTest {

    @TempDir
    java.nio.file.Path workspace;

    @Test
    void claimsWaitingTaskBeforeEnqueuingAWorkspaceContinuation() throws Exception {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        ProjectCheckoutLeaseService leases = mock(ProjectCheckoutLeaseService.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L); task.setStudentId(7); task.setProjectId(12); task.setConversationId("conv");
        task.setSessionId("session"); task.setMode("agent"); task.setStatus("waiting_workspace");
        StudentProject project = new StudentProject(); project.setWorkspacePath(Files.createDirectories(workspace).toString());
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(leases.isAvailable(eq(12), any())).thenReturn(true);
        when(executionLeases.instanceId()).thenReturn("instance-a");
        when(executionLeases.leaseDurationMs()).thenReturn(30_000L);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 2L, java.time.LocalDateTime.now().plusSeconds(30));
        when(lifecycle.claimDispatch(eq(71L), eq(AgentRunState.WAITING_WORKSPACE), eq(AgentRunState.QUEUED),
                any(), any(), any(), any(), any(), eq("instance-a"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease));
        AgentWorkspaceAdmissionScheduler scheduler = new AgentWorkspaceAdmissionScheduler(
                mapper, tasks, engine, projects, leases, lifecycle, executionLeases);

        boolean resumed = scheduler.resume(task);

        assertThat(resumed).isTrue();
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), eq(lease));
    }
}
