package com.labex.monitor.operation;

import com.labex.entity.OpsOperation;
import com.labex.mapper.OpsOperationMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.event.OpsEventRecordingService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/** 受控操作编排：幂等键去重 → 记录 REQUESTED → 委托执行器 → 终态落库 → 审计 + 事件。 */
@Service
public class OperationOrchestrator {

    private final OpsOperationMapper operationMapper;
    private final OperationIdempotencyService idempotencyService;
    private final AuditRecordingService auditService;
    private final OpsEventRecordingService eventService;

    public OperationOrchestrator(OpsOperationMapper operationMapper, OperationIdempotencyService idempotencyService,
                                 AuditRecordingService auditService, OpsEventRecordingService eventService) {
        this.operationMapper = operationMapper;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.eventService = eventService;
    }

    /** 幂等执行：返回已存在的操作或新执行结果。 */
    public OpsOperation execute(String actionType, String targetType, String targetId,
                                String idempotencyKey, String operatorId, String operatorRole,
                                String sourceIp, String reason, OperationExecutor executor) {
        OpsOperation existing = idempotencyService.findExisting(idempotencyKey);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        OpsOperation operation = new OpsOperation();
        operation.setActionType(actionType);
        operation.setTargetType(targetType);
        operation.setTargetId(targetId);
        operation.setStatus("REQUESTED");
        operation.setRequestedBy(truncate(operatorId, 160));
        operation.setSourceIp(truncate(sourceIp, 64));
        operation.setIdempotencyKey(idempotencyKey);
        operation.setRequestedAt(now);
        operationMapper.insert(operation);

        OperationExecutor.OperationResult result;
        try {
            result = executor.execute(new OperationExecutor.OperationContext(
                    actionType, targetType, targetId, reason));
        } catch (Exception failure) {
            result = OperationExecutor.OperationResult.failed("操作执行失败，请稍后重试");
        }
        operation.setStatus(result.succeeded() ? "SUCCEEDED" : "FAILED");
        operation.setResult(truncate(result.message(), 2048));
        if (!result.succeeded()) {
            operation.setFailureReason(truncate(result.message(), 1024));
        }
        operation.setCompletedAt(LocalDateTime.now());
        operationMapper.updateById(operation);

        auditService.record("OPS_" + actionType, targetType, targetId, operatorId, operatorRole, sourceIp,
                reason, null, operation.getStatus(), operation.getStatus(), null);
        eventService.record("OPS_OPERATION_" + operation.getStatus(), "info", "operation", targetType,
                targetId, actionType + " " + targetType + " " + targetId + " -> " + operation.getStatus(),
                null, operatorId);
        return operation;
    }

    private String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }
}