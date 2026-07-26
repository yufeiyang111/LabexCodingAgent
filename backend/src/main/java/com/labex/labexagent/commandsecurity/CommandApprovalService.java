package com.labex.labexagent.commandsecurity;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.CommandApproval;
import com.labex.mapper.CommandApprovalMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, exact-match command approval capabilities. This service deliberately has no wildcard,
 * remembered-grant, or caller-controlled approval path.
 */
@Service
public class CommandApprovalService {
    private static final String PENDING = "pending";
    private static final String APPROVED = "approved";
    private static final String REJECTED = "rejected";
    private static final String EXPIRED = "expired";
    private static final String CONSUMED = "consumed";

    private final CommandApprovalMapper approvalMapper;
    private final CommandAuditService auditService;

    public CommandApprovalService(CommandApprovalMapper approvalMapper) {
        this(approvalMapper, null);
    }

    @Autowired
    public CommandApprovalService(CommandApprovalMapper approvalMapper, CommandAuditService auditService) {
        this.approvalMapper = approvalMapper;
        this.auditService = auditService;
    }

    @Transactional(rollbackFor = Exception.class)
    public CommandApproval createOrGet(CreateRequest request) {
        validateCreate(request);
        CommandApproval existing = approvalMapper.selectOne(new QueryWrapper<CommandApproval>()
                .eq("student_id", request.studentId())
                .eq("project_id", request.projectId())
                .eq("idempotency_key", request.idempotencyKey()));
        if (existing != null) {
            if (!sameCreateBinding(existing, request)) {
                throw new IllegalArgumentException("Command approval idempotency conflict");
            }
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId(request.approvalId());
        approval.setIdempotencyKey(request.idempotencyKey());
        approval.setStudentId(request.studentId());
        approval.setProjectId(request.projectId());
        approval.setTaskId(request.taskId());
        approval.setConversationId(request.conversationId());
        approval.setSessionId(request.sessionId());
        approval.setSource(request.source());
        approval.setInvocationId(request.invocationId());
        approval.setToolCallId(request.toolCallId());
        approval.setCommandDigest(request.commandDigest());
        approval.setCanonicalCommand(request.canonicalCommand());
        approval.setDisplayCommand(request.displayCommand());
        approval.setWorkingDirectory(request.workingDirectory());
        approval.setShell(request.shell());
        approval.setCommandOptions(request.commandOptions());
        approval.setClassification(request.classification());
        approval.setPolicyVersion(request.policyVersion());
        approval.setStatus(PENDING);
        approval.setExpiresTime(request.expiresTime());
        approval.setCreateTime(now);
        approval.setUpdateTime(now);
        try {
            if (approvalMapper.insert(approval) != 1) {
                throw new IllegalStateException("Unable to persist command approval");
            }
            recordCreated(approval);
            return approval;
        } catch (DataIntegrityViolationException conflict) {
            CommandApproval concurrent = approvalMapper.selectOne(new QueryWrapper<CommandApproval>()
                    .eq("student_id", request.studentId())
                    .eq("project_id", request.projectId())
                    .eq("idempotency_key", request.idempotencyKey()));
            if (concurrent != null) {
                if (!sameCreateBinding(concurrent, request)) {
                    throw new IllegalArgumentException("Command approval idempotency conflict");
                }
                return concurrent;
            }
            throw conflict;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public CommandApproval decide(Integer studentId, Integer projectId, String approvalId,
                                  boolean approve, String decisionIdempotencyKey) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(approvalId, "approvalId");
        require(decisionIdempotencyKey, "decisionIdempotencyKey");

        CommandApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null || !Objects.equals(approval.getStudentId(), studentId)
                || !Objects.equals(approval.getProjectId(), projectId)) {
            throw new IllegalArgumentException("Command approval not found");
        }
        if (!PENDING.equals(approval.getStatus())) {
            return approval;
        }
        if (approval.getDecisionIdempotencyKey() != null) {
            return approval;
        }

        LocalDateTime now = LocalDateTime.now();
        if (!approval.getExpiresTime().isAfter(now)) {
            approvalMapper.update(null, new UpdateWrapper<CommandApproval>()
                    .eq("approval_id", approvalId)
                    .eq("student_id", studentId)
                    .eq("project_id", projectId)
                    .eq("status", PENDING)
                    .le("expires_time", now)
                    .set("status", EXPIRED)
                    .set("update_time", now));
            CommandApproval current = approvalMapper.selectById(approvalId);
            if (current != null && EXPIRED.equals(current.getStatus())) {
                recordDecision(current);
            }
            return current == null ? approval : current;
        }

        String decidedStatus = approve ? APPROVED : REJECTED;
        int updated = approvalMapper.update(null, new UpdateWrapper<CommandApproval>()
                .eq("approval_id", approvalId)
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("status", PENDING)
                .gt("expires_time", now)
                .set("status", decidedStatus)
                .set("decision_idempotency_key", decisionIdempotencyKey)
                .set("decision_time", now)
                .set("decided_by_student_id", studentId)
                .set("update_time", now));
        if (updated != 1) {
            CommandApproval current = approvalMapper.selectById(approvalId);
            if (current == null || !Objects.equals(current.getStudentId(), studentId)
                    || !Objects.equals(current.getProjectId(), projectId)) {
                throw new IllegalArgumentException("Command approval not found");
            }
            return current;
        }
        approval.setStatus(decidedStatus);
        approval.setDecisionIdempotencyKey(decisionIdempotencyKey);
        approval.setDecisionTime(now);
        approval.setDecidedByStudentId(studentId);
        approval.setUpdateTime(now);
        recordDecision(approval);
        return approval;
    }

    /** Returns the most recently updated approval for the owned durable task. */
    public CommandApproval findLatestForTask(Integer studentId, Integer projectId, Long taskId) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(taskId, "taskId");
        return approvalMapper.selectOne(new QueryWrapper<CommandApproval>()
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("task_id", taskId)
                .orderByDesc("update_time")
                .last("LIMIT 1"));
    }

    /** Returns only an approval capability owned by the authenticated project user. */
    public CommandApproval findOwned(Integer studentId, Integer projectId, String approvalId) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(approvalId, "approvalId");
        CommandApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null || !Objects.equals(approval.getStudentId(), studentId)
                || !Objects.equals(approval.getProjectId(), projectId)) {
            return null;
        }
        return approval;
    }

    /**
     * Consumes only the exact approval binding. A non-successful update intentionally returns false
     * without exposing whether the cause was expiry, replay, or a mismatched server invocation.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean consume(ConsumeRequest request) {
        validateConsume(request);
        LocalDateTime now = LocalDateTime.now();
        int updated = approvalMapper.update(null, new UpdateWrapper<CommandApproval>()
                .eq("approval_id", request.approvalId())
                .eq("student_id", request.studentId())
                .eq("project_id", request.projectId())
                .eq("task_id", request.taskId())
                .eq("conversation_id", request.conversationId())
                .eq("session_id", request.sessionId())
                .eq("source", request.source())
                .eq("invocation_id", request.invocationId())
                .eq("tool_call_id", request.toolCallId())
                .eq("command_digest", request.commandDigest())
                .eq("canonical_command", request.canonicalCommand())
                .eq("working_directory", request.workingDirectory())
                .eq("shell", request.shell())
                .eq("command_options", request.commandOptions())
                .eq("classification", request.classification())
                .eq("policy_version", request.policyVersion())
                .eq("status", APPROVED)
                .gt("expires_time", now)
                .set("status", CONSUMED)
                .set("consumed_time", now)
                .set("update_time", now));
        if (updated == 1 && auditService != null) {
            CommandApproval consumed = approvalMapper.selectById(request.approvalId());
            if (consumed == null) {
                throw new IllegalStateException("Consumed command approval disappeared");
            }
            auditService.recordExecutionClaimed(consumed);
        }
        return updated == 1;
    }

    private void recordCreated(CommandApproval approval) {
        if (auditService != null) {
            auditService.recordApprovalCreated(approval);
        }
    }

    private void recordDecision(CommandApproval approval) {
        if (auditService != null) {
            auditService.recordDecision(approval);
        }
    }

    private boolean sameCreateBinding(CommandApproval approval, CreateRequest request) {
        return Objects.equals(approval.getTaskId(), request.taskId())
                && Objects.equals(approval.getConversationId(), request.conversationId())
                && Objects.equals(approval.getSessionId(), request.sessionId())
                && Objects.equals(approval.getSource(), request.source())
                && Objects.equals(approval.getInvocationId(), request.invocationId())
                && Objects.equals(approval.getToolCallId(), request.toolCallId())
                && Objects.equals(approval.getCommandDigest(), request.commandDigest())
                && Objects.equals(approval.getCanonicalCommand(), request.canonicalCommand())
                && Objects.equals(approval.getWorkingDirectory(), request.workingDirectory())
                && Objects.equals(approval.getShell(), request.shell())
                && Objects.equals(approval.getCommandOptions(), request.commandOptions())
                && Objects.equals(approval.getClassification(), request.classification())
                && Objects.equals(approval.getPolicyVersion(), request.policyVersion());
    }

    private void validateCreate(CreateRequest request) {
        require(request, "request");
        require(request.approvalId(), "approvalId");
        require(request.idempotencyKey(), "idempotencyKey");
        validateBinding(request.studentId(), request.projectId(), request.taskId(), request.conversationId(),
                request.sessionId(), request.source(), request.invocationId(), request.toolCallId(),
                request.commandDigest(), request.canonicalCommand(), request.workingDirectory(), request.shell(),
                request.commandOptions(), request.classification(), request.policyVersion(), request.expiresTime());
    }

    private void validateConsume(ConsumeRequest request) {
        require(request, "request");
        require(request.approvalId(), "approvalId");
        validateBinding(request.studentId(), request.projectId(), request.taskId(), request.conversationId(),
                request.sessionId(), request.source(), request.invocationId(), request.toolCallId(),
                request.commandDigest(), request.canonicalCommand(), request.workingDirectory(), request.shell(),
                request.commandOptions(), request.classification(), request.policyVersion(), request.expiresTime());
    }

    private void validateBinding(Integer studentId, Integer projectId, Long taskId, String conversationId,
                                 String sessionId, String source, String invocationId, String toolCallId,
                                 String commandDigest, String canonicalCommand, String workingDirectory,
                                 String shell, String commandOptions, String classification,
                                 String policyVersion, LocalDateTime expiresTime) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(taskId, "taskId");
        require(conversationId, "conversationId");
        require(sessionId, "sessionId");
        require(source, "source");
        require(invocationId, "invocationId");
        require(toolCallId, "toolCallId");
        require(commandDigest, "commandDigest");
        require(canonicalCommand, "canonicalCommand");
        require(workingDirectory, "workingDirectory");
        require(shell, "shell");
        require(commandOptions, "commandOptions");
        require(classification, "classification");
        require(policyVersion, "policyVersion");
        require(expiresTime, "expiresTime");
    }

    private void require(Object value, String name) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record CreateRequest(
            String approvalId,
            String idempotencyKey,
            Integer studentId,
            Integer projectId,
            Long taskId,
            String conversationId,
            String sessionId,
            String source,
            String invocationId,
            String toolCallId,
            String commandDigest,
            String canonicalCommand,
            String displayCommand,
            String workingDirectory,
            String shell,
            String commandOptions,
            String classification,
            String policyVersion,
            LocalDateTime expiresTime) {
    }

    public record ConsumeRequest(
            String approvalId,
            Integer studentId,
            Integer projectId,
            Long taskId,
            String conversationId,
            String sessionId,
            String source,
            String invocationId,
            String toolCallId,
            String commandDigest,
            String canonicalCommand,
            String workingDirectory,
            String shell,
            String commandOptions,
            String classification,
            String policyVersion,
            LocalDateTime expiresTime) {
    }
}
