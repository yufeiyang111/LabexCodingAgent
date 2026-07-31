package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AgentRunInteractionDispatchClaimTest {

    @Test
    void resumesOnlyWhenTheInteractionClaimIsNewAndPassesItsLease() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask waiting = task("waiting_user");
        AgentTask recovering = task("recovering");
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 4L, LocalDateTime.of(2026, 7, 23, 10, 1));
        AgentRunInteraction interaction = interaction();
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(waiting, recovering);
        when(tasks.claimInteractionResume(eq(71L), eq("interaction-71"), any(), any()))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean resumed = scheduler.resumeIfWaiting(interaction);

        assertThat(resumed).isTrue();
        verify(engine).resume(eq(7), eq(12), any(AgentStreamRequest.class), eq(71L), eq(true), eq(lease));
    }

    private AgentTask task(String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(71L); task.setStudentId(7); task.setProjectId(12);
        task.setConversationId("conversation-1"); task.setSessionId("session-1");
        task.setMode("agent"); task.setStatus(status);
        return task;
    }

    private AgentRunInteraction interaction() {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("interaction-71");
        interaction.setTaskId(71L); interaction.setStudentId(7); interaction.setProjectId(12);
        interaction.setInteractionType("question"); interaction.setStatus("answered");
        interaction.setRequestPayload("{\"question\":\"Continue?\"}");
        interaction.setResponsePayload("{\"answer\":\"yes\"}");
        return interaction;
    }
}
