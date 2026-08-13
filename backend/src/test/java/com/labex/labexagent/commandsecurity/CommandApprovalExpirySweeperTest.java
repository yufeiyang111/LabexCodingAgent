package com.labex.labexagent.commandsecurity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.labex.entity.CommandApproval;
import com.labex.mapper.CommandApprovalMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandApprovalExpirySweeperTest {

    private CommandApproval expiredPending(String approvalId) {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId(approvalId);
        approval.setTaskId(71L);
        approval.setStatus("pending");
        approval.setDisplayCommand("mvn test");
        approval.setToolCallId("tool-71");
        approval.setExpiresTime(LocalDateTime.now().minusMinutes(5));
        return approval;
    }

    @Test
    void expiresOverduePendingApprovalsAndHandsThemToTheResumeScheduler() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        CommandApproval pending = expiredPending("approval-1");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending));
        when(mapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        CommandApprovalExpirySweeper sweeper = new CommandApprovalExpirySweeper(mapper, resumeScheduler);

        sweeper.sweepExpiredApprovals();

        verify(mapper).update(eq(null), any(Wrapper.class));
        verify(resumeScheduler).resumeIfWaiting(pending);
    }

    @Test
    void skipsExpiryWhenTheConditionalUpdateStopsMatching() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        CommandApproval pending = expiredPending("approval-2");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending));
        when(mapper.update(eq(null), any(Wrapper.class))).thenReturn(0);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        CommandApprovalExpirySweeper sweeper = new CommandApprovalExpirySweeper(mapper, resumeScheduler);

        sweeper.sweepExpiredApprovals();

        verify(mapper).update(eq(null), any(Wrapper.class));
        verify(resumeScheduler, never()).resumeIfWaiting(any());
    }

    @Test
    void doesNothingWhenNoOverduePendingApprovalsExist() {
        CommandApprovalMapper mapper = mock(CommandApprovalMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        CommandApprovalExpirySweeper sweeper = new CommandApprovalExpirySweeper(mapper, resumeScheduler);

        sweeper.sweepExpiredApprovals();

        verify(mapper, never()).update(eq(null), any(Wrapper.class));
        verify(resumeScheduler, never()).resumeIfWaiting(any());
    }
}
