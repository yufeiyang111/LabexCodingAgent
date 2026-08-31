package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EventDto {
    private Long eventId;
    private String eventType;
    private String severity;
    private String source;
    private String targetType;
    private String targetId;
    private String message;
    private String detail;
    private String operator;
    private LocalDateTime createTime;
}