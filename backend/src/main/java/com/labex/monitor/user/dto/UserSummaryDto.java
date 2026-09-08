package com.labex.monitor.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSummaryDto {
    private Integer userId;
    private String username;
    private String displayName;
    private String email;
    private String role;
    private Integer status;
    private boolean online;
    private String lastActiveTime;
    private String createTime;
    private int projectCount;
    private int taskCount;
    private double taskSuccessRate;
    private long totalTokens;
}
