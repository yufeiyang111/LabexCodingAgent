package com.labex.monitor.alert;

import com.labex.entity.OpsAlert;
import com.labex.entity.OpsAlertRule;
import com.labex.entity.OpsMetricSample;
import com.labex.mapper.OpsMetricSampleMapper;
import com.labex.monitor.config.OpsAlertProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 告警评估：以持久化采样为唯一指标源，按规则窗口最新样本判定越界，并处理自动恢复与静默到期。 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);
    private static final int SAMPLE_LOOKUP_LIMIT = 1000;

    private final AlertRuleService ruleService;
    private final AlertDeduplicationService deduplicationService;
    private final AlertStateService stateService;
    private final AlertNotificationService notificationService;
    private final OpsMetricSampleMapper sampleMapper;
    private final OpsAlertProperties properties;

    public AlertEvaluationService(AlertRuleService ruleService, AlertDeduplicationService deduplicationService,
                                  AlertStateService stateService, AlertNotificationService notificationService,
                                  OpsMetricSampleMapper sampleMapper, OpsAlertProperties properties) {
        this.ruleService = ruleService;
        this.deduplicationService = deduplicationService;
        this.stateService = stateService;
        this.notificationService = notificationService;
        this.sampleMapper = sampleMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${labex-agent.monitor.alert.evaluation-interval-ms:60000}")
    public void evaluateScheduled() {
        try {
            evaluateOnce();
        } catch (Exception failure) {
            log.warn("Alert evaluation cycle failed: {}", safeMessage(failure));
        }
    }

    public int evaluateOnce() {
        int evaluated = 0;
        for (OpsAlertRule rule : ruleService.list(true)) {
            try {
                evaluateRule(rule);
                evaluated++;
                if (evaluated >= properties.getEvaluationBatchSize()) {
                    break;
                }
            } catch (Exception failure) {
                log.warn("Alert evaluation skipped rule {}: {}", rule.getName(), safeMessage(failure));
            }
        }
        return evaluated;
    }

    private void evaluateRule(OpsAlertRule rule) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(valueOrZero(rule.getDurationMinutes()));
        List<OpsMetricSample> samples = sampleMapper.selectSince(windowStart);
        OpsMetricSample latest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        Double value = latest == null ? null : extract(latest, rule.getMetricKey());
        boolean violated = value != null && compare(value, rule.getThreshold(), rule.getOperator());
        String fingerprint = deduplicationService.fingerprintFor(rule.getRuleId());

        if (violated) {
            OpsAlert active = deduplicationService.findActive(rule.getRuleId(), fingerprint);
            if (active == null) {
                OpsAlert fired = stateService.open(rule.getRuleId(), fingerprint, rule.getSeverity(),
                        rule.getMetricKey(), value, rule.getThreshold(),
                        buildMessage(rule, value));
                notificationService.notifyFiring(fired);
            } else if ("SILENCED".equals(normalize(active.getStatus()))
                    && active.getSilencedUntil() != null
                    && !active.getSilencedUntil().isAfter(LocalDateTime.now())) {
                if (stateService.reactivate(active.getAlertId())) {
                    notificationService.notifyFiring(active);
                }
            } else {
                stateService.open(rule.getRuleId(), fingerprint, rule.getSeverity(),
                        rule.getMetricKey(), value, rule.getThreshold(), buildMessage(rule, value));
            }
        } else {
            OpsAlert active = deduplicationService.findActive(rule.getRuleId(), fingerprint);
            if (active != null && !"RESOLVED".equals(normalize(active.getStatus()))) {
                stateService.resolve(active.getAlertId(), "system", "condition cleared");
            }
        }
    }

    private Double extract(OpsMetricSample sample, String metricKey) {
        switch (metricKey) {
            case "cpu_percent":
                return sample.getCpuPercent();
            case "memory_percent":
                return sample.getMemoryPercent();
            case "disk_percent":
                return sample.getDiskPercent();
            case "heap_used_ratio":
                if (sample.getHeapMaxBytes() == null || sample.getHeapMaxBytes() <= 0) {
                    return null;
                }
                return sample.getHeapUsedBytes() == null ? null
                        : sample.getHeapUsedBytes() * 100.0 / sample.getHeapMaxBytes();
            case "system_load":
                return sample.getSystemLoadAverage();
            case "task_running":
                return sample.getTaskRunning() == null ? null : sample.getTaskRunning().doubleValue();
            case "task_waiting":
                return sample.getTaskWaiting() == null ? null : sample.getTaskWaiting().doubleValue();
            case "task_failed":
                return sample.getTaskFailed() == null ? null : sample.getTaskFailed().doubleValue();
            case "task_total":
                return sample.getTaskTotal() == null ? null : sample.getTaskTotal().doubleValue();
            case "token_total":
                return sample.getTokenTotal() == null ? null : sample.getTokenTotal().doubleValue();
            default:
                return null;
        }
    }

    private boolean compare(Double value, Double threshold, String operator) {
        String op = operator == null ? "GT" : operator.toUpperCase(java.util.Locale.ROOT);
        switch (op) {
            case "GTE":
                return value >= threshold;
            case "LT":
                return value < threshold;
            case "LTE":
                return value <= threshold;
            default:
                return value > threshold;
        }
    }

    private String buildMessage(OpsAlertRule rule, Double value) {
        return rule.getName() + " exceeded: " + rule.getMetricKey() + " " + value + " "
                + rule.getOperator() + " " + rule.getThreshold();
    }

    private String normalize(String status) {
        return status == null ? "" : status.toUpperCase(java.util.Locale.ROOT);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}