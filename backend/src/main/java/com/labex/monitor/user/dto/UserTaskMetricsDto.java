package com.labex.monitor.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTaskMetricsDto {
    private int totalTasks;
    private int completedTasks;
    private int failedTasks;
    private int runningTasks;
}
