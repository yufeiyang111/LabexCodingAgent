package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class OperationDto {
    private Long operationId;
    private String actionType;
    private String targetType;
    private String targetId;
    private String status;
    private String requestedBy;
    private String sourceIp;
    private String idempotencyKey;
    private String result;
    private String failureReason;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
}