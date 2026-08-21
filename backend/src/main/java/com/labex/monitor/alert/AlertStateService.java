package com.labex.monitor.alert;

import com.labex.entity.OpsAlert;
import com.labex.mapper.OpsAlertMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.event.OpsEventRecordingService;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 告警状态机：FIRING / ACKNOWLEDGED / RESOLVED / SILENCED，迁移幂等并记录审计与事件。 */
@Service
public class AlertStateService {

    private final OpsAlertMapper alertMapper;
    private final AlertDeduplicationService deduplicationService;
    private final AuditRecordingService auditService;
    private final OpsEventRecordingService eventService;

    public AlertStateService(OpsAlertMapper alertMapper, AlertDeduplicationService deduplicationService,
                             AuditRecordingService auditService, OpsEventRecordingService eventService) {
        this.alertMapper = alertMapper;
        this.deduplicationService = deduplicationService;
        this.auditService = auditService;
        this.eventService = eventService;
    }

    /** 新建告警；同规则同指纹已有活跃告警时改为刷新，不重复创建。 */
    public OpsAlert open(Long ruleId, String fingerprint, String severity, String metricKey,
                         Double currentValue, Double threshold, String message) {
        OpsAlert existing = deduplicationService.findActive(ruleId, fingerprint);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setCurrentValue(currentValue);
            existing.setLastFiringAt(now);
            existing.setSeverity(severity);
            alertMapper.updateById(existing);
            return existing;
        }
        OpsAlert alert = new OpsAlert();
        alert.setRuleId(ruleId);
        alert.setFingerprint(fingerprint);
        alert.setStatus("FIRING");
        alert.setSeverity(severity);
        alert.setMetricKey(metricKey);
        alert.setCurrentValue(currentValue);
        alert.setThreshold(threshold);
        alert.setMessage(truncate(message, 1024));
        alert.setFirstFiringAt(now);
        alert.setLastFiringAt(now);
        alertMapper.insert(alert);
        eventService.record("ALERT_FIRED", severity, "alert", "alert", String.valueOf(alert.getAlertId()),
                message, null, "system");
        return alert;
    }

    /** 确认告警；已是确认态幂等返回 true，终态返回 false。 */
    public boolean acknowledge(Long alertId, String operator) {
        OpsAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            return false;
        }
        String status = normalize(alert.getStatus());
        if ("RESOLVED".equals(status)) {
            return false;
        }
        if ("ACKNOWLEDGED".equals(status)) {
            return true;
        }
        alert.setStatus("ACKNOWLEDGED");
        alert.setAcknowledgedAt(LocalDateTime.now());
        alert.setAcknowledgedBy(truncate(operator, 160));
        alertMapper.updateById(alert);
        auditService.record("ALERT_ACKNOWLEDGE", "alert", String.valueOf(alertId), operator, null, null,
                "acknowledged alert " + alertId, status, "ACKNOWLEDGED", "SUCCESS", null);
        eventService.record("ALERT_ACKNOWLEDGED", alert.getSeverity(), "alert", "alert",
                String.valueOf(alertId), "alert " + alertId + " acknowledged", null, operator);
        return true;
    }

    /** 静默告警；静默期间不再触发新通知，到期由评估恢复逻辑接管。 */
    public boolean silence(Long alertId, int durationMinutes, String operator) {
        if (durationMinutes < 1 || durationMinutes > 10080) {
            throw new IllegalArgumentException("静默时长必须为 1-10080 分钟");
        }
        OpsAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            return false;
        }
        String status = normalize(alert.getStatus());
        if ("RESOLVED".equals(status)) {
            return false;
        }
        if ("SILENCED".equals(status)) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        alert.setStatus("SILENCED");
        alert.setSilencedAt(now);
        alert.setSilencedUntil(now.plusMinutes(durationMinutes));
        alertMapper.updateById(alert);
        auditService.record("ALERT_SILENCE", "alert", String.valueOf(alertId), operator, null, null,
                "silenced alert " + alertId + " for " + durationMinutes + "m", status, "SILENCED", "SUCCESS", null);
        eventService.record("ALERT_SILENCED", alert.getSeverity(), "alert", "alert",
                String.valueOf(alertId), "alert " + alertId + " silenced", null, operator);
        return true;
    }

    /** 解决告警；已解决幂等返回 true。 */
    public boolean resolve(Long alertId, String operator, String reason) {
        OpsAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            return false;
        }
        String status = normalize(alert.getStatus());
        if ("RESOLVED".equals(status)) {
            return true;
        }
        alert.setStatus("RESOLVED");
        alert.setResolvedAt(LocalDateTime.now());
        alert.setResolvedBy(truncate(operator, 160));
        alertMapper.updateById(alert);
        auditService.record("ALERT_RESOLVE", "alert", String.valueOf(alertId), operator, null, null,
                reason == null || reason.isBlank() ? "resolved alert " + alertId : truncate(reason, 1024),
                status, "RESOLVED", "SUCCESS", null);
        eventService.record("ALERT_RESOLVED", alert.getSeverity(), "alert", "alert",
                String.valueOf(alertId), "alert " + alertId + " resolved", null, operator);
        return true;
    }

    /** 静默到期后评估发现不再越界时由评估循环调用，完成静默→解决。 */
    public boolean resolveExpiredSilence(Long alertId) {
        OpsAlert alert = alertMapper.selectById(alertId);
        if (alert == null || !"SILENCED".equals(normalize(alert.getStatus()))) {
            return false;
        }
        return resolve(alertId, "system", "silence expired and condition cleared");
    }

    /** 静默到期后评估发现仍越界时调用：SILENCED → FIRING 并重新触发通知。 */
    public boolean reactivate(Long alertId) {
        OpsAlert alert = alertMapper.selectById(alertId);
        if (alert == null || !"SILENCED".equals(normalize(alert.getStatus()))) {
            return false;
        }
        alert.setStatus("FIRING");
        alert.setLastFiringAt(LocalDateTime.now());
        alertMapper.updateById(alert);
        eventService.record("ALERT_REACTIVATED", alert.getSeverity(), "alert", "alert",
                String.valueOf(alertId), "alert " + alertId + " reactivated after silence", null, "system");
        return true;
    }

    private String normalize(String status) {
        return status == null ? "" : status.toUpperCase(Locale.ROOT);
    }

    private String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }
}