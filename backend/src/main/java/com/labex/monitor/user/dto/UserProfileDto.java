package com.labex.monitor.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDto {
    private Integer userId;
    private String username;
    private String displayName;
    private String email;
    private String role;
    private Integer status;
    private String createTime;
    private String lastLoginIp;
    private String lastLoginLocation;
}
