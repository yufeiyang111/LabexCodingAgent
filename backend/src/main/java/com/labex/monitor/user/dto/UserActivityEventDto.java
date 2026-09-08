package com.labex.monitor.user.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActivityEventDto {
    private String eventId;
    private String timestamp;
    private String category;
    private String action;
    private String title;
    private String details;
    private String status;
    private Map<String, Object> metadata;
}
