package com.labex.monitor.operation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsOperation;
import com.labex.mapper.OpsOperationMapper;
import com.labex.monitor.dto.OperationDto;
import java.util.List;
import org.springframework.stereotype.Service;

/** 受控操作记录查询。 */
@Service
public class OperationQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OpsOperationMapper operationMapper;

    public OperationQueryService(OpsOperationMapper operationMapper) {
        this.operationMapper = operationMapper;
    }

    public List<OperationDto> query(int page, int pageSize, String actionType, String targetType, String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<OpsOperation> wrapper = new LambdaQueryWrapper<OpsOperation>()
                .eq(actionType != null && !actionType.isBlank(), OpsOperation::getActionType, actionType)
                .eq(targetType != null && !targetType.isBlank(), OpsOperation::getTargetType, targetType)
                .eq(status != null && !status.isBlank(), OpsOperation::getStatus, status.toUpperCase())
                .orderByDesc(OpsOperation::getRequestedAt);
        Page<OpsOperation> pageResult = operationMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return pageResult.getRecords().stream().map(this::toDto).toList();
    }

    private OperationDto toDto(OpsOperation operation) {
        OperationDto dto = new OperationDto();
        dto.setOperationId(operation.getOperationId());
        dto.setActionType(operation.getActionType());
        dto.setTargetType(operation.getTargetType());
        dto.setTargetId(operation.getTargetId());
        dto.setStatus(operation.getStatus());
        dto.setRequestedBy(operation.getRequestedBy());
        dto.setSourceIp(operation.getSourceIp());
        dto.setIdempotencyKey(operation.getIdempotencyKey());
        dto.setResult(operation.getResult());
        dto.setFailureReason(operation.getFailureReason());
        dto.setRequestedAt(operation.getRequestedAt());
        dto.setCompletedAt(operation.getCompletedAt());
        return dto;
    }
}