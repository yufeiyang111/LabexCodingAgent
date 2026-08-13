package com.labex.labexagent.commandsecurity;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.CommandApproval;
import com.labex.mapper.CommandApprovalMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically expires pending command approvals whose TTL has passed, so a task stuck in
 * waiting_approval is never left hanging forever when the user never decides. Expiring here hands
 * the approval to the existing resume scheduler, which wakes the task and lets the model reassess.
 */
@Service
public class CommandApprovalExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(CommandApprovalExpirySweeper.class);
    private static final int BATCH_SIZE = 100;

    private final CommandApprovalMapper approvalMapper;
    private final CommandApprovalResumeScheduler resumeScheduler;

    public CommandApprovalExpirySweeper(CommandApprovalMapper approvalMapper,
                                        CommandApprovalResumeScheduler resumeScheduler) {
        this.approvalMapper = approvalMapper;
        this.resumeScheduler = resumeScheduler;
    }

    @Scheduled(fixedDelayString = "${labex-agent.command-approval.expiry-sweep-interval-ms:30000}")
    public void sweepExpiredApprovals() {
        List<CommandApproval> expired = approvalMapper.selectList(new QueryWrapper<CommandApproval>()
                .eq("status", "pending")
                .lt("expires_time", LocalDateTime.now())
                .orderByAsc("expires_time")
                .last("LIMIT " + BATCH_SIZE));
        if (expired == null || expired.isEmpty()) {
            return;
        }
        for (CommandApproval approval : expired) {
            expire(approval);
        }
    }

    private void expire(CommandApproval approval) {
        if (approval == null || approval.getApprovalId() == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int updated = approvalMapper.update(null, new UpdateWrapper<CommandApproval>()
                    .eq("approval_id", approval.getApprovalId())
                    .eq("status", "pending")
                    .le("expires_time", now)
                    .set("status", "expired")
                    .set("update_time", now));
            if (updated != 1) {
                return;
            }
            approval.setStatus("expired");
            approval.setUpdateTime(now);
            log.info("COMMAND_APPROVAL_EXPIRED approvalId={} taskId={} displayCommand={}",
                    approval.getApprovalId(), approval.getTaskId(), approval.getDisplayCommand());
            resumeScheduler.resumeIfWaiting(approval);
        } catch (RuntimeException failure) {
            log.warn("COMMAND_APPROVAL_EXPIRY_FAILED approvalId={} taskId={} reason={}",
                    approval.getApprovalId(), approval.getTaskId(), failure.getMessage());
        }
    }
}
