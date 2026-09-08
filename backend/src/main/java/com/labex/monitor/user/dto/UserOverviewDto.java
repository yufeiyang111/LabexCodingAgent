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
public class UserOverviewDto {
    private int onlineCount;
    private int todayDau;
    private double dauChangeRatio;
    private int weeklyWau;
    private long totalRegistered;
    private long avgTokensPerActiveUser;
    private double avgTasksPerActiveUser;
    private List<UserTrendPointDto> trendSeries;
}
