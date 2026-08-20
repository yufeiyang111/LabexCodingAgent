package com.labex.monitor.dto;

import lombok.Data;

/** 状态码分布。 */
@Data
public class StatusStat {
    private Integer status;
    private Long count;
}