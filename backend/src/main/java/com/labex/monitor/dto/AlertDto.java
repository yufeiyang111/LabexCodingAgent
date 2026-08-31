package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AlertDto {
    private Long alertId;
    private Long ruleId;
    private String ruleName;
    private String fingerprint;
    private String status;
    private String severity;
    private String metricKey;
    private Double currentValue;
    private Double threshold;
    private String message;
    private LocalDateTime firstFiringAt;
    private LocalDateTime lastFiringAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime silencedUntil;
    private String acknowledgedBy;
    private String resolvedBy;
}