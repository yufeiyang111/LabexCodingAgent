package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AuditRecordDto {
    private Long auditId;
    private String operatorId;
    private String operatorRole;
    private String sourceIp;
    private String actionType;
    private String targetType;
    private String targetId;
    private String reason;
    private String beforeState;
    private String afterState;
    private String result;
    private String failureReason;
    private LocalDateTime createTime;
}