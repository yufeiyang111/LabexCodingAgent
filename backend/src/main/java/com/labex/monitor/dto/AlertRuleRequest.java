package com.labex.monitor.dto;

import lombok.Data;

@Data
public class AlertRuleRequest {
    private String name;
    private String metricKey;
    private String operator;
    private Double threshold;
    private Integer durationMinutes;
    private Integer cooldownMinutes;
    private String severity;
    private Boolean enabled;
    private String description;
}