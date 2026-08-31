package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class IncidentDto {
    private Long incidentId;
    private String title;
    private String severity;
    private String status;
    private Long sourceAlertId;
    private String summary;
    private LocalDateTime openedAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String acknowledgedBy;
    private String resolvedBy;
    private LocalDateTime createTime;
}