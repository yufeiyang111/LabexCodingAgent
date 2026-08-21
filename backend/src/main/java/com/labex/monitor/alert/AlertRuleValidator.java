package com.labex.monitor.alert;

import com.labex.monitor.dto.AlertRuleRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 告警规则参数校验：指标键白名单、比较符白名单、数值范围。 */
@Component
public class AlertRuleValidator {

    public static final Set<String> METRIC_KEYS = Set.of(
            "cpu_percent", "memory_percent", "disk_percent", "heap_used_ratio",
            "system_load", "task_running", "task_waiting", "task_failed", "task_total", "token_total");

    public static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE");

    public static final Set<String> SEVERITIES = Set.of("info", "warning", "critical");

    public void validate(AlertRuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("规则不能为空");
        }
        if (request.getName() == null || request.getName().isBlank()
                || request.getName().length() > 128) {
            throw new IllegalArgumentException("规则名称必须为 1-128 个字符");
        }
        if (request.getMetricKey() == null || !METRIC_KEYS.contains(request.getMetricKey())) {
            throw new IllegalArgumentException("不支持的指标键: " + request.getMetricKey());
        }
        if (request.getOperator() == null || !OPERATORS.contains(request.getOperator())) {
            throw new IllegalArgumentException("不支持的比较符: " + request.getOperator());
        }
        if (request.getThreshold() == null || !Double.isFinite(request.getThreshold())) {
            throw new IllegalArgumentException("阈值必须为有限数值");
        }
        if (request.getDurationMinutes() == null || request.getDurationMinutes() < 1
                || request.getDurationMinutes() > 1440) {
            throw new IllegalArgumentException("评估窗口必须为 1-1440 分钟");
        }
        if (request.getCooldownMinutes() == null || request.getCooldownMinutes() < 0
                || request.getCooldownMinutes() > 10080) {
            throw new IllegalArgumentException("冷却时间必须为 0-10080 分钟");
        }
        if (request.getSeverity() == null || !SEVERITIES.contains(request.getSeverity())) {
            throw new IllegalArgumentException("不支持的严重度: " + request.getSeverity());
        }
        if (request.getDescription() != null && request.getDescription().length() > 512) {
            throw new IllegalArgumentException("描述不能超过 512 个字符");
        }
    }
}