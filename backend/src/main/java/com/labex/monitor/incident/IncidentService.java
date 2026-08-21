package com.labex.monitor.incident;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsIncident;
import com.labex.mapper.OpsIncidentMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.dto.EventDto;
import com.labex.monitor.dto.IncidentDto;
import com.labex.monitor.event.OpsEventRecordingService;
import com.labex.monitor.event.OpsEventQueryService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** 故障生命周期：创建（可关联告警）、状态迁移（状态机约束）、事件时间线投影。 */
@Service
public class IncidentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OpsIncidentMapper incidentMapper;
    private final IncidentStateMachine stateMachine;
    private final AuditRecordingService auditService;
    private final OpsEventRecordingService eventService;
    private final OpsEventQueryService eventQueryService;

    public IncidentService(OpsIncidentMapper incidentMapper, IncidentStateMachine stateMachine,
                           AuditRecordingService auditService, OpsEventRecordingService eventService,
                           OpsEventQueryService eventQueryService) {
        this.incidentMapper = incidentMapper;
        this.stateMachine = stateMachine;
        this.auditService = auditService;
        this.eventService = eventService;
        this.eventQueryService = eventQueryService;
    }

    public OpsIncident create(String title, String severity, Long sourceAlertId, String summary, String operator) {
        if (title == null || title.isBlank() || title.length() > 256) {
            throw new IllegalArgumentException("故障标题必须为 1-256 个字符");
        }
        if (severity == null || !severity.matches("info|warning|critical")) {
            throw new IllegalArgumentException("不支持的严重度: " + severity);
        }
        if (summary != null && summary.length() > 2048) {
            throw new IllegalArgumentException("摘要不能超过 2048 个字符");
        }
        LocalDateTime now = LocalDateTime.now();
        OpsIncident incident = new OpsIncident();
        incident.setTitle(title);
        incident.setSeverity(severity);
        incident.setStatus(IncidentStateMachine.OPEN);
        incident.setSourceAlertId(sourceAlertId);
        incident.setSummary(summary);
        incident.setOpenedAt(now);
        incidentMapper.insert(incident);
        auditService.record("INCIDENT_CREATE", "incident", String.valueOf(incident.getIncidentId()), operator,
                null, null, "created incident", null, "OPEN", "SUCCESS", null);
        eventService.record("INCIDENT_OPENED", severity, "incident", "incident",
                String.valueOf(incident.getIncidentId()), title, null, operator);
        return incident;
    }

    public List<IncidentDto> query(int page, int pageSize, String status, String severity) {
        String s = normalize(status);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<OpsIncident> wrapper = new LambdaQueryWrapper<OpsIncident>().orderByDesc(OpsIncident::getOpenedAt);
        if (s != null) {
            wrapper.eq(OpsIncident::getStatus, s);
        }
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(OpsIncident::getSeverity, severity);
        }
        Page<OpsIncident> pageResult = incidentMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return pageResult.getRecords().stream().map(this::toDto).toList();
    }

    public long count(String status, String severity) {
        String s = normalize(status);
        LambdaQueryWrapper<OpsIncident> wrapper = new LambdaQueryWrapper<>();
        if (s != null) {
            wrapper.eq(OpsIncident::getStatus, s);
        }
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(OpsIncident::getSeverity, severity);
        }
        return incidentMapper.selectCount(wrapper);
    }

    private static String normalize(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase();
    }

    public OpsIncident detail(Long incidentId) {
        return incidentId == null ? null : incidentMapper.selectById(incidentId);
    }

    public List<EventDto> timeline(Long incidentId) {
        return eventQueryService.query(1, 200, null, null, "incident", String.valueOf(incidentId));
    }

    /** 状态迁移；非法迁移抛 IllegalArgumentException，幂等迁移返回 true。 */
    public boolean transition(Long incidentId, String targetStatus, String operator, String reason) {
        OpsIncident incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            return false;
        }
        String from = incident.getStatus();
        String violation = stateMachine.transition(from, targetStatus);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        if (from.equalsIgnoreCase(targetStatus)) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        incident.setStatus(targetStatus);
        switch (targetStatus) {
            case IncidentStateMachine.ACKNOWLEDGED:
                incident.setAcknowledgedAt(now);
                incident.setAcknowledgedBy(operator);
                break;
            case IncidentStateMachine.RESOLVED:
                incident.setResolvedAt(now);
                incident.setResolvedBy(operator);
                break;
            case IncidentStateMachine.CLOSED:
                incident.setClosedAt(now);
                break;
            default:
                break;
        }
        incidentMapper.updateById(incident);
        auditService.record("INCIDENT_TRANSITION", "incident", String.valueOf(incidentId), operator, null, null,
                reason == null || reason.isBlank() ? from + " -> " + targetStatus : reason,
                from, targetStatus, "SUCCESS", null);
        eventService.record("INCIDENT_" + targetStatus, incident.getSeverity(), "incident", "incident",
                String.valueOf(incidentId), "incident " + incidentId + " " + from + " -> " + targetStatus,
                null, operator);
        return true;
    }

    private IncidentDto toDto(OpsIncident incident) {
        IncidentDto dto = new IncidentDto();
        dto.setIncidentId(incident.getIncidentId());
        dto.setTitle(incident.getTitle());
        dto.setSeverity(incident.getSeverity());
        dto.setStatus(incident.getStatus());
        dto.setSourceAlertId(incident.getSourceAlertId());
        dto.setSummary(incident.getSummary());
        dto.setOpenedAt(incident.getOpenedAt());
        dto.setAcknowledgedAt(incident.getAcknowledgedAt());
        dto.setResolvedAt(incident.getResolvedAt());
        dto.setClosedAt(incident.getClosedAt());
        dto.setAcknowledgedBy(incident.getAcknowledgedBy());
        dto.setResolvedBy(incident.getResolvedBy());
        dto.setCreateTime(incident.getCreateTime());
        return dto;
    }
}