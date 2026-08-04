package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.CommandApproval;
import com.labex.entity.CommandAuditEvent;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.mapper.CommandAuditEventMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CommandAuditServiceTest {

    @Test
    void persistsAIdempotentRunningExecutionMarkerBeforeProcessStarts() {
        CommandAuditEventMapper mapper = mock(CommandAuditEventMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(CommandAuditEvent.class))).thenAnswer(invocation -> {
            CommandAuditEvent event = invocation.getArgument(0);
            event.setEventId(2L);
            return 1;
        });
        CommandAuditService service = new CommandAuditService(mapper);

        CommandAuditEvent event = service.recordExecutionStarted(approval());

        assertThat(event.getEventType()).isEqualTo("EXECUTION_STARTED");
        assertThat(event.getExecutionStatus()).isEqualTo("running");
        assertThat(event.getIdempotencyKey()).contains("execution-started");
        assertThat(event.getOutputDigest()).isNull();
        verify(mapper).insert(event);
    }

    @Test
    void persistsOnlyDigestAndBoundedExecutionMetadata() {
        CommandAuditEventMapper mapper = mock(CommandAuditEventMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(CommandAuditEvent.class))).thenAnswer(invocation -> {
            CommandAuditEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            return 1;
        });
        CommandAuditService service = new CommandAuditService(mapper);

        CommandAuditEvent event = service.recordExecutionOutcome(approval(), new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 12, "--token=sentinel-secret", false), 12L);

        assertThat(event.getCommandDigest()).isEqualTo("digest-71");
        assertThat(event.getOutputDigest()).isNotBlank().doesNotContain("sentinel-secret");
        assertThat(event.getOutputSizeBytes()).isPositive();
        verify(mapper).insert(event);
    }

    private CommandApproval approval() {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setTaskId(71L);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_shell");
        approval.setInvocationId("invoke-71");
        approval.setToolCallId("tool-71");
        approval.setCommandDigest("digest-71");
        approval.setCanonicalCommand("npm test --token=sentinel-secret");
        approval.setClassification("REQUIRE_APPROVAL");
        approval.setPolicyVersion("policy-v1");
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(10));
        return approval;
    }
}
