package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.workspace.ProjectCheckoutLeaseService;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentWorkspaceDispatchClaimTest {

    @Test
    void resumesOnlyTheWorkerThatDurablyClaimedWorkspaceAdmission() throws Exception {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        ProjectCheckoutLeaseService checkout = mock(ProjectCheckoutLeaseService.class);
        AgentTask task = task();
        Path workspace = Files.createTempDirectory("labex-dispatch-claim-");
        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspace.toString());
        when(leases.instanceId()).thenReturn("instance-a");
        when(leases.leaseDurationMs()).thenReturn(30_000L);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 2L, LocalDateTime.of(2026, 7, 23, 10, 1));
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(task));
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(checkout.isAvailable(eq(12), any(Path.class))).thenReturn(true);
        when(lifecycle.claimDispatch(eq(71L), eq(AgentRunState.WAITING_WORKSPACE), eq(AgentRunState.QUEUED),
                any(), any(), any(), any(), any(), eq("instance-a"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease));
        AgentWorkspaceAdmissionScheduler scheduler = new AgentWorkspaceAdmissionScheduler(
                mapper, tasks, engine, projects, checkout, lifecycle, leases);

        boolean resumed = scheduler.resume(task);

        assertThat(resumed).isTrue();
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), eq(lease));
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L); task.setStudentId(7); task.setProjectId(12);
        task.setConversationId("conversation-1"); task.setSessionId("session-1");
        task.setMode("agent"); task.setStatus("waiting_workspace");
        return task;
    }
}
