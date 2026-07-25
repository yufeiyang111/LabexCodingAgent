package com.labex.labexagent.commandsecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.CommandApproval;
import com.labex.mapper.CommandApprovalMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class CommandApprovalServiceTest {

    @Test
    void persistsACompleteServerOwnedApprovalBinding() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(CommandApproval.class))).thenReturn(1);
        CommandApprovalService service = new CommandApprovalService(mapper);

        CommandApproval approval = service.createOrGet(createRequest());

        assertEquals("pending", approval.getStatus());
        assertEquals("digest-71", approval.getCommandDigest());
        assertEquals("invoke-71", approval.getInvocationId());
        assertEquals("tool-71", approval.getToolCallId());
        verify(mapper).insert(approval);
    }

    @Test
    void returnsExistingApprovalForTheSameOwnerScopedIdempotencyKey() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        CommandApproval existing = matchingPersisted("pending");
        when(mapper.selectOne(any())).thenReturn(existing);
        CommandApprovalService service = new CommandApprovalService(mapper);

        assertEquals(existing, service.createOrGet(createRequest()));
        verify(mapper, never()).insert(any(CommandApproval.class));
    }

    @Test
    void returnsTheConcurrentApprovalWhenOwnerScopedIdempotencyInsertConflicts() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        CommandApproval existing = matchingPersisted("pending");
        when(mapper.selectOne(any())).thenReturn(null, existing);
        when(mapper.insert(any(CommandApproval.class))).thenThrow(new DataIntegrityViolationException("duplicate"));
        CommandApprovalService service = new CommandApprovalService(mapper);

        assertEquals(existing, service.createOrGet(createRequest()));
        verify(mapper).insert(any(CommandApproval.class));
    }

    @Test
    void recordsOwnedApprovalWithAConditionalPendingUpdate() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        CommandApproval pending = persisted("pending");
        when(mapper.selectById("approval-71")).thenReturn(pending);
        when(mapper.update(eq(null), any())).thenReturn(1);
        CommandApprovalService service = new CommandApprovalService(mapper);

        CommandApproval decided = service.decide(7, 12, "approval-71", true, "decision-71");

        assertEquals("approved", decided.getStatus());
        ArgumentCaptor<UpdateWrapper<CommandApproval>> update = updateCaptor();
        verify(mapper).update(eq(null), update.capture());
        String condition = update.getValue().getSqlSegment();
        assertTrue(condition.contains("approval_id"));
        assertTrue(condition.contains("student_id"));
        assertTrue(condition.contains("project_id"));
        assertTrue(condition.contains("status"));
        assertTrue(condition.contains("expires_time"));
    }

    @Test
    void consumeReturnsFalseWhenTheAtomicConditionalUpdateDoesNotMatch() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        when(mapper.update(eq(null), any())).thenReturn(0);
        CommandApprovalService service = new CommandApprovalService(mapper);

        assertFalse(service.consume(consumeRequest()));

        ArgumentCaptor<UpdateWrapper<CommandApproval>> update = updateCaptor();
        verify(mapper).update(eq(null), update.capture());
        String condition = update.getValue().getSqlSegment();
        assertTrue(condition.contains("approval_id"));
        assertTrue(condition.contains("command_digest"));
        assertTrue(condition.contains("canonical_command"));
        assertTrue(condition.contains("working_directory"));
        assertTrue(condition.contains("invocation_id"));
        assertTrue(condition.contains("tool_call_id"));
        assertTrue(condition.contains("status"));
        assertTrue(condition.contains("expires_time"));
    }

    @Test
    void consumeReturnsTrueOnlyWhenTheAtomicConditionalUpdateConsumesOneRow() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        when(mapper.update(eq(null), any())).thenReturn(1);
        CommandApprovalService service = new CommandApprovalService(mapper);

        assertTrue(service.consume(consumeRequest()));
    }

    @Test
    void rejectsIdempotencyKeyReuseForADifferentCommandBinding() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        CommandApproval existing = persisted("pending");
        existing.setTaskId(71L);
        existing.setConversationId("conversation-71");
        existing.setSessionId("session-71");
        existing.setSource("agent_tool");
        existing.setInvocationId("invoke-71");
        existing.setToolCallId("tool-71");
        existing.setCommandDigest("digest-71");
        existing.setCanonicalCommand("npm test");
        existing.setWorkingDirectory(".");
        existing.setShell("direct");
        existing.setCommandOptions("timeout=60");
        existing.setClassification("require_approval");
        existing.setPolicyVersion("policy-v1");
        when(mapper.selectOne(any())).thenReturn(existing);
        CommandApprovalService service = new CommandApprovalService(mapper);

        CommandApprovalService.CreateRequest changed = new CommandApprovalService.CreateRequest(
                "approval-new", "request-71", 7, 12, 71L, "conversation-71", "session-71",
                "agent_tool", "invoke-71", "tool-71", "digest-changed", "npm test -- --changed", "npm test",
                ".", "direct", "timeout=60", "require_approval", "policy-v1",
                LocalDateTime.now().plusMinutes(10));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.createOrGet(changed));
        verify(mapper, never()).insert(any(CommandApproval.class));
    }

    @Test
    void consumesOnlyOnceWhenTheConditionalUpdateStopsMatching() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        when(mapper.update(eq(null), any())).thenReturn(1, 0);
        CommandApprovalService service = new CommandApprovalService(mapper);

        assertTrue(service.consume(consumeRequest()));
        assertFalse(service.consume(consumeRequest()));
    }

    private ArgumentCaptor<UpdateWrapper<CommandApproval>> updateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
    }

    private CommandApprovalService.CreateRequest createRequest() {
        return new CommandApprovalService.CreateRequest(
                "approval-71", "request-71", 7, 12, 71L, "conversation-71", "session-71",
                "agent_tool", "invoke-71", "tool-71", "digest-71", "npm test", "npm test",
                ".", "direct", "timeout=60", "require_approval", "policy-v1",
                LocalDateTime.now().plusMinutes(10));
    }

    private CommandApprovalService.ConsumeRequest consumeRequest() {
        return new CommandApprovalService.ConsumeRequest(
                "approval-71", 7, 12, 71L, "conversation-71", "session-71", "agent_tool",
                "invoke-71", "tool-71", "digest-71", "npm test", ".", "direct", "timeout=60",
                "require_approval", "policy-v1", LocalDateTime.now().plusMinutes(10));
    }

    private CommandApproval matchingPersisted(String status) {
        CommandApproval approval = persisted(status);
        approval.setTaskId(71L);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_tool");
        approval.setInvocationId("invoke-71");
        approval.setToolCallId("tool-71");
        approval.setCommandDigest("digest-71");
        approval.setCanonicalCommand("npm test");
        approval.setWorkingDirectory(".");
        approval.setShell("direct");
        approval.setCommandOptions("timeout=60");
        approval.setClassification("require_approval");
        approval.setPolicyVersion("policy-v1");
        return approval;
    }

    private CommandApproval persisted(String status) {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setStatus(status);
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(10));
        return approval;
    }
}
