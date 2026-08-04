package com.labex.labexagent.commandsecurity;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.CommandApproval;
import com.labex.entity.CommandAuditEvent;
import com.labex.labexagent.execution.ProcessExecutionIdentity;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.mapper.CommandAuditEventMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Writes nonsecret, append-only lifecycle evidence for one-time command approvals. Command text
 * and process output never enter these records; only their digests and bounded metadata do.
 */
@Service
public class CommandAuditService {
    private final CommandAuditEventMapper auditMapper;

    public CommandAuditService(CommandAuditEventMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public CommandAuditEvent recordApprovalCreated(CommandApproval approval) {
        return record(approval, "APPROVAL_CREATED", "", "", null, null, null,
                eventKey(approval, "created"));
    }

    public CommandAuditEvent recordDecision(CommandApproval approval) {
        String decision = approval == null ? "" : approval.getStatus();
        return record(approval, "APPROVAL_DECIDED", decision, "", null, null, null,
                eventKey(approval, "decision:" + decision + ":" + approval.getDecisionIdempotencyKey()));
    }

    public CommandAuditEvent recordExecutionClaimed(CommandApproval approval) {
        return record(approval, "EXECUTION_CLAIMED", "", "claimed", null, null, null,
                eventKey(approval, "execution-claimed"));
    }

    /** Records the durable command execution boundary before the worker process is started. */
    public CommandAuditEvent recordExecutionStarted(CommandApproval approval) {
        return record(approval, "EXECUTION_STARTED", "", "running", null, null, null,
                eventKey(approval, "execution-started"));
    }

    /** 在调用方等待命令结束前持久化真实进程身份。 */
    public CommandAuditEvent recordExecutionProcessBound(
            CommandApproval approval, ProcessExecutionIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("process identity is required");
        }
        return record(approval, "EXECUTION_PROCESS_BOUND", "", "running", null, null, null,
                eventKey(approval, "process-bound:" + identity.processId() + ":"
                        + (identity.processStartEpochMs() == null ? "unknown" : identity.processStartEpochMs())),
                identity);
    }

    public CommandAuditEvent recordExecutionOutcome(CommandApproval approval,
                                                     ProcessExecutionResult result,
                                                     long durationMs) {
        String output = result == null || result.output() == null ? "" : result.output();
        String status = result != null && result.succeeded() ? "succeeded" : "failed";
        Integer exitCode = result == null ? null : result.exitCode();
        return record(approval, "EXECUTION_" + status.toUpperCase(), "", status, exitCode,
                Math.max(0L, durationMs), output,
                eventKey(approval, "execution-outcome:" + status));
    }

    public CommandAuditEvent recordExecutionInterrupted(CommandApproval approval, String reasonCode) {
        return record(approval, "EXECUTION_INTERRUPTED", "", "interrupted", null, null, null,
                eventKey(approval, "execution-interrupted:" + safeToken(reasonCode)));
    }

    /** Returns only the latest nonsecret execution metadata needed to restore an approval card after refresh. */
    public CommandAuditEvent findLatestExecutionOutcome(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            return null;
        }
        return auditMapper.selectOne(new QueryWrapper<CommandAuditEvent>()
                .eq("approval_id", approvalId)
                .isNotNull("execution_status")
                .orderByDesc("event_id")
                .last("LIMIT 1"));
    }

    private CommandAuditEvent record(CommandApproval approval, String eventType, String decision,
                                     String executionStatus, Integer exitCode, Long durationMs,
                                     String output, String idempotencyKey) {
        return record(approval, eventType, decision, executionStatus, exitCode, durationMs,
                output, idempotencyKey, null);
    }

    private CommandAuditEvent record(CommandApproval approval, String eventType, String decision,
                                     String executionStatus, Integer exitCode, Long durationMs,
                                     String output, String idempotencyKey, ProcessExecutionIdentity identity) {
        requireApproval(approval);
        CommandAuditEvent existing = auditMapper.selectOne(new QueryWrapper<CommandAuditEvent>()
                .eq("approval_id", approval.getApprovalId())
                .eq("idempotency_key", idempotencyKey));
        if (existing != null) {
            return existing;
        }
        CommandAuditEvent event = new CommandAuditEvent();
        event.setApprovalId(approval.getApprovalId());
        event.setStudentId(approval.getStudentId());
        event.setProjectId(approval.getProjectId());
        event.setTaskId(approval.getTaskId());
        event.setConversationId(approval.getConversationId());
        event.setSessionId(approval.getSessionId());
        event.setEventType(eventType);
        event.setSource(approval.getSource());
        event.setInvocationId(approval.getInvocationId());
        event.setToolCallId(approval.getToolCallId());
        event.setCommandDigest(approval.getCommandDigest());
        event.setClassification(approval.getClassification());
        event.setPolicyVersion(approval.getPolicyVersion());
        event.setDecision(emptyToNull(decision));
        event.setExecutionStatus(emptyToNull(executionStatus));
        event.setExitCode(exitCode);
        event.setDurationMs(durationMs);
        if (identity != null) {
            event.setProcessHostId(identity.hostId());
            event.setProcessOwner(identity.ownerId());
            event.setWorkerRuntime(identity.workerRuntime());
            event.setWorkerRunId(identity.workerRunId());
            event.setProcessId(identity.processId());
            event.setProcessStartEpochMs(identity.processStartEpochMs());
            event.setProcessLeaseExpiresEpochMs(identity.leaseExpiresEpochMs());
        }
        if (output != null) {
            event.setOutputDigest(sha256(output));
            event.setOutputSizeBytes((long) output.getBytes(StandardCharsets.UTF_8).length);
        }
        event.setIdempotencyKey(idempotencyKey);
        event.setCreateTime(LocalDateTime.now());
        try {
            if (auditMapper.insert(event) != 1) {
                throw new IllegalStateException("Unable to persist command audit event");
            }
            return event;
        } catch (DataIntegrityViolationException conflict) {
            CommandAuditEvent concurrent = auditMapper.selectOne(new QueryWrapper<CommandAuditEvent>()
                    .eq("approval_id", approval.getApprovalId())
                    .eq("idempotency_key", idempotencyKey));
            if (concurrent != null) {
                return concurrent;
            }
            throw conflict;
        }
    }

    private String eventKey(CommandApproval approval, String suffix) {
        return "command-audit:v1:" + approval.getApprovalId() + ":" + suffix;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safeToken(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void requireApproval(CommandApproval approval) {
        if (approval == null || approval.getApprovalId() == null || approval.getApprovalId().isBlank()) {
            throw new IllegalArgumentException("approval is required");
        }
    }
}
