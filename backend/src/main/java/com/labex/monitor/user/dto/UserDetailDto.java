package com.labex.monitor.user.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDetailDto {
    private UserProfileDto profile;
    private List<TokenBreakdownDto> tokenBreakdown;
    private UserTaskMetricsDto taskMetrics;
    private List<UserProjectDto> projects;
}
