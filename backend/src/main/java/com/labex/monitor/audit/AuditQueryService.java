package com.labex.monitor.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsAuditLog;
import com.labex.mapper.OpsAuditLogMapper;
import com.labex.monitor.dto.AuditRecordDto;
import java.util.List;
import org.springframework.stereotype.Service;

/** 审计查询：按操作者、动作类型、目标过滤分页。 */
@Service
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OpsAuditLogMapper auditLogMapper;

    public AuditQueryService(OpsAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public List<AuditRecordDto> query(int page, int pageSize, String operatorId, String actionType,
                                      String targetType, String targetId, String result) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<OpsAuditLog> wrapper = new LambdaQueryWrapper<OpsAuditLog>()
                .eq(operatorId != null && !operatorId.isBlank(), OpsAuditLog::getOperatorId, operatorId)
                .eq(actionType != null && !actionType.isBlank(), OpsAuditLog::getActionType, actionType)
                .eq(targetType != null && !targetType.isBlank(), OpsAuditLog::getTargetType, targetType)
                .eq(targetId != null && !targetId.isBlank(), OpsAuditLog::getTargetId, targetId)
                .eq(result != null && !result.isBlank(), OpsAuditLog::getResult, result)
                .orderByDesc(OpsAuditLog::getCreateTime);
        Page<OpsAuditLog> pageResult = auditLogMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return pageResult.getRecords().stream().map(this::toDto).toList();
    }

    public long count(String operatorId, String actionType, String targetType, String targetId, String result) {
        LambdaQueryWrapper<OpsAuditLog> wrapper = new LambdaQueryWrapper<OpsAuditLog>()
                .eq(operatorId != null && !operatorId.isBlank(), OpsAuditLog::getOperatorId, operatorId)
                .eq(actionType != null && !actionType.isBlank(), OpsAuditLog::getActionType, actionType)
                .eq(targetType != null && !targetType.isBlank(), OpsAuditLog::getTargetType, targetType)
                .eq(targetId != null && !targetId.isBlank(), OpsAuditLog::getTargetId, targetId)
                .eq(result != null && !result.isBlank(), OpsAuditLog::getResult, result);
        return auditLogMapper.selectCount(wrapper);
    }

    private AuditRecordDto toDto(OpsAuditLog log) {
        AuditRecordDto dto = new AuditRecordDto();
        dto.setAuditId(log.getAuditId());
        dto.setOperatorId(log.getOperatorId());
        dto.setOperatorRole(log.getOperatorRole());
        dto.setSourceIp(log.getSourceIp());
        dto.setActionType(log.getActionType());
        dto.setTargetType(log.getTargetType());
        dto.setTargetId(log.getTargetId());
        dto.setReason(log.getReason());
        dto.setBeforeState(log.getBeforeState());
        dto.setAfterState(log.getAfterState());
        dto.setResult(log.getResult());
        dto.setFailureReason(log.getFailureReason());
        dto.setCreateTime(log.getCreateTime());
        return dto;
    }
}