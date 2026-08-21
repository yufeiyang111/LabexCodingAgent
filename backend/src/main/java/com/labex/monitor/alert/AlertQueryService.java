package com.labex.monitor.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsAlert;
import com.labex.entity.OpsAlertRule;
import com.labex.mapper.OpsAlertMapper;
import com.labex.monitor.dto.AlertDto;
import java.util.List;
import org.springframework.stereotype.Service;

/** 告警查询：状态/严重度过滤 + 分页，附规则名。 */
@Service
public class AlertQueryService {

    private static final List<String> VALID_STATUSES = List.of("FIRING", "ACKNOWLEDGED", "RESOLVED", "SILENCED");
    private static final int MAX_PAGE_SIZE = 100;

    private final OpsAlertMapper alertMapper;
    private final AlertRuleService ruleService;

    public AlertQueryService(OpsAlertMapper alertMapper, AlertRuleService ruleService) {
        this.alertMapper = alertMapper;
        this.ruleService = ruleService;
    }

    public List<AlertDto> query(int page, int pageSize, String status, String severity) {
        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(status.toUpperCase())) {
            throw new IllegalArgumentException("不支持的告警状态: " + status);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<OpsAlert> wrapper = new LambdaQueryWrapper<OpsAlert>()
                .eq(status != null && !status.isBlank(), OpsAlert::getStatus, status.toUpperCase())
                .eq(severity != null && !severity.isBlank(), OpsAlert::getSeverity, severity)
                .orderByDesc(OpsAlert::getLastFiringAt);
        Page<OpsAlert> pageResult = alertMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return pageResult.getRecords().stream().map(this::toDto).toList();
    }

    public long count(String status, String severity) {
        LambdaQueryWrapper<OpsAlert> wrapper = new LambdaQueryWrapper<OpsAlert>()
                .eq(status != null && !status.isBlank(), OpsAlert::getStatus, status.toUpperCase())
                .eq(severity != null && !severity.isBlank(), OpsAlert::getSeverity, severity);
        return alertMapper.selectCount(wrapper);
    }

    public AlertDto detail(Long alertId) {
        OpsAlert alert = alertMapper.selectById(alertId);
        return alert == null ? null : toDto(alert);
    }

    private AlertDto toDto(OpsAlert alert) {
        AlertDto dto = new AlertDto();
        dto.setAlertId(alert.getAlertId());
        dto.setRuleId(alert.getRuleId());
        OpsAlertRule rule = ruleService.get(alert.getRuleId());
        dto.setRuleName(rule == null ? null : rule.getName());
        dto.setFingerprint(alert.getFingerprint());
        dto.setStatus(alert.getStatus());
        dto.setSeverity(alert.getSeverity());
        dto.setMetricKey(alert.getMetricKey());
        dto.setCurrentValue(alert.getCurrentValue());
        dto.setThreshold(alert.getThreshold());
        dto.setMessage(alert.getMessage());
        dto.setFirstFiringAt(alert.getFirstFiringAt());
        dto.setLastFiringAt(alert.getLastFiringAt());
        dto.setAcknowledgedAt(alert.getAcknowledgedAt());
        dto.setResolvedAt(alert.getResolvedAt());
        dto.setSilencedUntil(alert.getSilencedUntil());
        dto.setAcknowledgedBy(alert.getAcknowledgedBy());
        dto.setResolvedBy(alert.getResolvedBy());
        return dto;
    }
}