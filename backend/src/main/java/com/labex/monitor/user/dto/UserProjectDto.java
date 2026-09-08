package com.labex.monitor.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProjectDto {
    private Integer projectId;
    private String projectName;
    private String updateTime;
}
